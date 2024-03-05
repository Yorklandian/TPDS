/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.controller

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.event.Logging.InfoLevel
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import kamon.Kamon
import org.apache.openwhisk.common.Https.HttpsConfig
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.connector.MessagingProvider
import org.apache.openwhisk.core.containerpool.logging.LogStoreProvider
import org.apache.openwhisk.core.database.{ActivationStoreProvider, CacheChangeNotification, RemoteCacheInvalidation}
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity.ActivationId.ActivationIdGenerator
import org.apache.openwhisk.core.entity.ExecManifest.Runtimes
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.loadBalancer.LoadBalancerProvider
import org.apache.openwhisk.http.CorsSettings.RespondWithServerCorsHeaders
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.{BasicHttpService, BasicRasService}
import org.apache.openwhisk.spi.SpiLoader
import pureconfig._
import spray.json.DefaultJsonProtocol._
import spray.json._
import pureconfig.generic.auto._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}
import scala.collection.mutable.{ArrayBuffer, Map, PriorityQueue}

/**
 * The Controller is the service that provides the REST API for OpenWhisk.
 *
 * It extends the BasicRasService so it includes a ping endpoint for monitoring.
 *
 * Akka sends messages to akka Actors -- the Controller is an Actor, ready to receive messages.
 *
 * It is possible to deploy a hot-standby controller. Each controller needs its own instance. This instance is a
 * consecutive numbering, starting with 0.
 * The state and cache of each controller is not shared to the other controllers.
 * If the base controller crashes, the hot-standby controller will be used. After the base controller is up again,
 * it will be used again. Because of the empty cache after restart, there are no problems with inconsistency.
 * The only problem that could occur is, that the base controller is not reachable, but does not restart. After switching
 * back to the base controller, there could be an inconsistency in the cache (e.g. if a user has updated an action). This
 * inconsistency will be resolved by its own after removing the cached item, 5 minutes after it has been generated.
 *
 * Uses the Akka routing DSL: http://doc.akka.io/docs/akka-http/current/scala/http/routing-dsl/overview.html
 *
 * @param config A set of properties needed to run an instance of the controller service
 * @param instance if running in scale-out, a unique identifier for this instance in the group
 * @param verbosity logging verbosity
 * @param executionContext Scala runtime support for concurrent operations
 */
class Controller(val instance: ControllerInstanceId,
                 runtimes: Runtimes,
                 implicit val whiskConfig: WhiskConfig,
                 implicit val actorSystem: ActorSystem,
                 implicit val logging: Logging)
    extends BasicRasService
    with RespondWithServerCorsHeaders {

  TransactionId.controller.mark(
    this,
    LoggingMarkers.CONTROLLER_STARTUP(instance.asString),
    s"starting controller instance ${instance.asString}",
    logLevel = InfoLevel)

  /**
   * A Route in Akka is technically a function taking a RequestContext as a parameter.
   *
   * The "~" Akka DSL operator composes two independent Routes, building a routing tree structure.
   *
   * @see http://doc.akka.io/docs/akka-http/current/scala/http/routing-dsl/routes.html#composing-routes
   */
  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ {
      (pathEndOrSingleSlash & get) {
        complete(info)
      }
    } ~ apiV1.routes ~ swagger.swaggerRoutes ~ adminRoutes
  }

  // initialize datastores
  private implicit val authStore = WhiskAuthStore.datastore()
  private implicit val entityStore = WhiskEntityStore.datastore()
  private implicit val cacheChangeNotification = Some(new CacheChangeNotification {
    val remoteCacheInvalidaton = new RemoteCacheInvalidation(whiskConfig, "controller", instance)
    override def apply(k: CacheKey) = {
      remoteCacheInvalidaton.invalidateWhiskActionMetaData(k)
      remoteCacheInvalidaton.notifyOtherInstancesAboutInvalidation(k)
    }
  })

  // initialize backend services
  private implicit val loadBalancer =
    SpiLoader.get[LoadBalancerProvider].instance(whiskConfig, instance)
  logging.info(this, s"loadbalancer initialized: ${loadBalancer.getClass.getSimpleName}")(TransactionId.controller)

  private implicit val entitlementProvider =
    SpiLoader.get[EntitlementSpiProvider].instance(whiskConfig, loadBalancer, instance)
  private implicit val activationIdFactory = new ActivationIdGenerator {}
  private implicit val logStore = SpiLoader.get[LogStoreProvider].instance(actorSystem)
  private implicit val activationStore =
    SpiLoader.get[ActivationStoreProvider].instance(actorSystem, logging)

  // register collections
  Collection.initialize(entityStore)

  /** The REST APIs. */
  implicit val controllerInstance = instance
  private val apiV1 = new RestAPIVersion(whiskConfig, "api", "v1")
  private val swagger = new SwaggerDocs(Uri.Path.Empty, "infoswagger.json")

  /**
   * Handles GET /invokers - list of invokers
   *             /invokers/healthy/count - nr of healthy invokers
   *             /invokers/ready - 200 in case # of healthy invokers are above the expected value
   *                             - 500 in case # of healthy invokers are bellow the expected value
   *
   * @return JSON with details of invoker health or count of healthy invokers respectively.
   */
  protected[controller] val internalInvokerHealth = {
    implicit val executionContext = actorSystem.dispatcher
    (pathPrefix("invokers") & get) {
      pathEndOrSingleSlash {
        complete {
          loadBalancer
            .invokerHealth()
            .map(_.map(i => i.id.toString -> i.status.asString).toMap.toJson.asJsObject)
        }
      } ~ path("healthy" / "count") {
        complete {
          loadBalancer
            .invokerHealth()
            .map(_.count(_.status == InvokerState.Healthy).toJson)
        }
      } ~ path("ready") {
        onSuccess(loadBalancer.invokerHealth()) { invokersHealth =>
          val all = invokersHealth.size
          val healthy = invokersHealth.count(_.status == InvokerState.Healthy)
          val ready = Controller.readyState(all, healthy, Controller.readinessThreshold.getOrElse(1))
          if (ready)
            complete(JsObject("healthy" -> s"$healthy/$all".toJson))
          else
            complete(InternalServerError -> JsObject("unhealthy" -> s"${all - healthy}/$all".toJson))
        }
      }
    }
  }

  /**
   * Handles GET /activation URI.
   *
   * @return running activation
   */
  protected[controller] val activationStatus = {
    implicit val executionContext = actorSystem.dispatcher
    (pathPrefix("activation") & get) {
      pathEndOrSingleSlash {
        complete(loadBalancer.activeActivationsByController.map(_.toJson))
      } ~ path("count") {
        complete(loadBalancer.activeActivationsByController(controllerInstance.asString).map(_.toJson))
      }
    }
  }

  // controller top level info
  private val info = Controller.info(
    whiskConfig,
    TimeLimit.config,
    MemoryLimit.config,
    LogLimit.config,
    runtimes,
    List(apiV1.basepath()))

  private val controllerUsername = loadConfigOrThrow[String](ConfigKeys.whiskControllerUsername)
  private val controllerPassword = loadConfigOrThrow[String](ConfigKeys.whiskControllerPassword)

  /**
   * disable controller
   */
  private def disable(implicit transid: TransactionId) = {
    implicit val executionContext = actorSystem.dispatcher
    implicit val jsonPrettyResponsePrinter = PrettyPrinter
    (path("disable") & post) {
      extractCredentials {
        case Some(BasicHttpCredentials(username, password)) =>
          if (username == controllerUsername && password == controllerPassword) {
            loadBalancer.close
            logging.warn(this, "controller is disabled")
            complete("controller is disabled")
          } else {
            terminate(StatusCodes.Unauthorized, "username or password are wrong.")
          }
        case _ => terminate(StatusCodes.Unauthorized)
      }
    }
  }

  private def adminRoutes(implicit transid: TransactionId) = {
    sendCorsHeaders {
      options {
        complete(OK)
      } ~ internalInvokerHealth ~ activationStatus ~ disable
    }
  }
}

/**
 * Singleton object provides a factory to create and start an instance of the Controller service.
 */
object Controller {

  protected val protocol = loadConfigOrThrow[String]("whisk.controller.protocol")
  protected val interface = loadConfigOrThrow[String]("whisk.controller.interface")
  protected val readinessThreshold = loadConfig[Double]("whisk.controller.readiness-fraction")

  val topicPrefix = loadConfigOrThrow[String](ConfigKeys.kafkaTopicsPrefix)
  val userEventTopicPrefix = loadConfigOrThrow[String](ConfigKeys.kafkaTopicsUserEventPrefix)

  // requiredProperties is a Map whose keys define properties that must be bound to
  // a value, and whose values are default values.   A null value in the Map means there is
  // no default value specified, so it must appear in the properties file
  def requiredProperties =
    ExecManifest.requiredProperties ++
      RestApiCommons.requiredProperties ++
      SpiLoader.get[LoadBalancerProvider].requiredProperties ++
      EntitlementProvider.requiredProperties

  private def info(config: WhiskConfig,
                   timeLimit: TimeLimitConfig,
                   memLimit: MemoryLimitConfig,
                   logLimit: MemoryLimitConfig,
                   runtimes: Runtimes,
                   apis: List[String]) =
    JsObject(
      "description" -> "OpenWhisk".toJson,
      "support" -> JsObject(
        "github" -> "https://github.com/apache/openwhisk/issues".toJson,
        "slack" -> "http://slack.openwhisk.org".toJson),
      "api_paths" -> apis.toJson,
      "limits" -> JsObject(
        "actions_per_minute" -> config.actionInvokePerMinuteLimit.toInt.toJson,
        "triggers_per_minute" -> config.triggerFirePerMinuteLimit.toInt.toJson,
        "concurrent_actions" -> config.actionInvokeConcurrentLimit.toInt.toJson,
        "sequence_length" -> config.actionSequenceLimit.toInt.toJson,
        "default_min_action_duration" -> TimeLimit.namespaceDefaultConfig.min.toMillis.toJson,
        "default_max_action_duration" -> TimeLimit.namespaceDefaultConfig.max.toMillis.toJson,
        "default_min_action_memory" -> MemoryLimit.namespaceDefaultConfig.min.toBytes.toJson,
        "default_max_action_memory" -> MemoryLimit.namespaceDefaultConfig.max.toBytes.toJson,
        "default_min_action_logs" -> LogLimit.namespaceDefaultConfig.min.toBytes.toJson,
        "default_max_action_logs" -> LogLimit.namespaceDefaultConfig.max.toBytes.toJson,
        "min_action_duration" -> timeLimit.min.toMillis.toJson,
        "max_action_duration" -> timeLimit.max.toMillis.toJson,
        "min_action_memory" -> memLimit.min.toBytes.toJson,
        "max_action_memory" -> memLimit.max.toBytes.toJson,
        "min_action_logs" -> logLimit.min.toBytes.toJson,
        "max_action_logs" -> logLimit.max.toBytes.toJson),
      "runtimes" -> runtimes.toJson)

  def readyState(allInvokers: Int, healthyInvokers: Int, readinessThreshold: Double): Boolean = {
    if (allInvokers > 0) (healthyInvokers / allInvokers) >= readinessThreshold else false
  }

  def main(args: Array[String]): Unit = {
    implicit val actorSystem = ActorSystem("controller-actor-system")
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    start(args)
  }

  def start(args: Array[String])(implicit actorSystem: ActorSystem, logger: Logging): Unit = {
    ConfigMXBean.register()
    Kamon.init()

    // Prepare Kamon shutdown
    CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdownKamon") { () =>
      logger.info(this, s"Shutting down Kamon with coordinated shutdown")
      Kamon.stopModules().map(_ => Done)(Implicits.global)
    }

    // extract configuration data from the environment
    val config = new WhiskConfig(requiredProperties)
    val port = config.servicePort.toInt

    // if deploying multiple instances (scale out), must pass the instance number as the
    require(args.length >= 1, "controller instance required")
    val instance = ControllerInstanceId(args(0))

    def abort(message: String) = {
      logger.error(this, message)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
      sys.exit(1)
    }

    if (!config.isValid) {
      abort("Bad configuration, cannot start.")
    }

    val msgProvider = SpiLoader.get[MessagingProvider]

    Seq(
      (topicPrefix + "completed" + instance.asString, "completed", Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)),
      (topicPrefix + "health", "health", None),
      (topicPrefix + "cacheInvalidation", "cache-invalidation", None),
      (userEventTopicPrefix + "events", "events", None)).foreach {
      case (topic, topicConfigurationKey, maxMessageBytes) =>
        if (msgProvider.ensureTopic(config, topic, topicConfigurationKey, maxMessageBytes).isFailure) {
          abort(s"failure during msgProvider.ensureTopic for topic $topic")
        }
    }

    ExecManifest.initialize(config) match {
      case Success(_) =>
        val controller = new Controller(instance, ExecManifest.runtimesManifest, config, actorSystem, logger)

        val httpsConfig =
          if (Controller.protocol == "https") Some(loadConfigOrThrow[HttpsConfig]("whisk.controller.https")) else None

        BasicHttpService.startHttpService(controller.route, port, httpsConfig, interface)(actorSystem)

      case Failure(t) =>
        abort(s"Invalid runtimes manifest: $t")
    }
  }
}
class ActivationRecorder {
  // Function to calculate Cut-off Point based on the recorded execution curve
  def calculateCutOffPoint(executionCurve: ArrayBuffer[Double], systemPressureCurve: ArrayBuffer[Double]): Double = {
    val slopeThreshold = 0.1 // Threshold for detecting slope change
    val executionTimeThresholdFactor = 2 // Factor for selecting the execution time threshold
    val systemPressureThreshold = 0.8 // Threshold for selecting the system pressure

    // Calculate slope changes in the execution curve
    val slopeChanges = executionCurve.sliding(2).map(pair => (pair(1) - pair(0)) / pair(0)).toArray

    // Find the index where slope abruptly changes
    val abruptChangeIndex = slopeChanges.indexWhere(slope => Math.abs(slope) > slopeThreshold)

    if (abruptChangeIndex >= 0) {
      // Cut-off Point is the point where the slope abruptly changes
      executionCurve(abruptChangeIndex + 1)
    } else {
      // If no slope transition points, consider scenarios based on execution time and system pressure
      val minExecutionTime = executionCurve.min
      val maxExecutionTime = executionCurve.max

      if (maxExecutionTime / minExecutionTime > executionTimeThresholdFactor) {
        // If execution time steadily increases, select the point at N times of the minimum execution time
        minExecutionTime * executionTimeThresholdFactor
      } else {
        // If execution time hardly changes, select the point at M% of system pressure
        val systemPressureThresholdIndex = systemPressureCurve.indexWhere(pressure => pressure >= systemPressureThreshold)
        if (systemPressureThresholdIndex >= 0) {
          systemPressureCurve(systemPressureThresholdIndex)
        } else {
          // Default: Return the maximum execution time if no suitable point is found
          maxExecutionTime
        }
      }
    }
  }
}



class DAGNode(val id: Int, val executionTime: Double)

class DAGEdge(val from: DAGNode, val to: DAGNode)

class M_D_P {
  // Data structures for representing DAG
  val nodes = ArrayBuffer[DAGNode]()
  val edges = ArrayBuffer[DAGEdge]()

  // Data structures for MDP
  val subGraphs = ArrayBuffer[ArrayBuffer[DAGNode]]()
  val workNodes = ArrayBuffer[WorkNode]()
  val crucialPath = ArrayBuffer[DAGNode]()

  // Function to calculate crucial path using Mechanism Two
  def calculateCrucialPath() {
    // Start with the first node in the DAG
    for (node <- nodes) {
      val currentPath = ArrayBuffer[DAGNode]()
      currentPath += node
      calculateCrucialPathRecursive(node, currentPath)
    }

    // Find the path with the maximum total execution time
    val maxPath = crucialPath.maxBy(path => path.map(_.executionTime).sum)
    crucialPath.clear()
    crucialPath ++= maxPath
  }

  // Recursive function to calculate crucial path
  private def calculateCrucialPathRecursive(currentNode: DAGNode, currentPath: ArrayBuffer[DAGNode]) {
    // Base case: If the node has no outgoing edges, update the crucial path
    if (!edges.exists(_.from == currentNode)) {
      if (currentPath.length > crucialPath.length) {
        crucialPath.clear()
        crucialPath ++= currentPath
      }
    } else {
      // Recursive case: Explore outgoing edges
      for (edge <- edges.filter(_.from == currentNode)) {
        val nextNode = edge.to
        val newPath = currentPath.clone()
        newPath += nextNode
        calculateCrucialPathRecursive(nextNode, newPath)
      }
    }
  }

  // Function to perform horizontal merging using Two-Stream Merge algorithm (Mechanism Four)
  def horizontalMerge(nodes: ArrayBuffer[DAGNode]) {
    // Sort nodes by execution time in descending order
    val sortedNodes = nodes.sortBy(_.executionTime)(Ordering[Double].reverse)

    // Two-Stream Merge algorithm
    val pCrucial = crucialPath.last
    val (crucialNodes, otherNodes) = sortedNodes.partition(_.id == pCrucial.id)

    val mergedSubGraphs = ArrayBuffer[ArrayBuffer[DAGNode]]()

    for (crucialNode <- crucialNodes) {
      val subGraph = ArrayBuffer(crucialNode)

      // Find nodes with the highest sharing scores
      val highestSharingNodes = otherNodes.sortBy(sharingScore(_, crucialNode))

      for (node <- highestSharingNodes) {
        if (!isPressureExceeded(subGraph, node) && !isBenefitBelowThreshold(subGraph, node)) {
          subGraph += node
        }
      }

      // Add the sub-graph to the list of merged sub-graphs
      mergedSubGraphs += subGraph
    }

    // Add remaining nodes to the merged sub-graphs
    for (node <- otherNodes) {
      if (!mergedSubGraphs.exists(_.contains(node))) {
        mergedSubGraphs.minBy(subGraph => subGraph.map(_.executionTime).sum) += node
      }
    }

    // Update subGraphs with the merged sub-graphs
    subGraphs.clear()
    subGraphs ++= mergedSubGraphs
  }

  // Function to perform vertical merging (Mechanism Four)
  def verticalMerge(subGraphs: ArrayBuffer[graph]) {
    // Sort sub-graphs by execution time in descending order
    val sortedSubGraphs = subGraphs.sortBy(subGraph => subGraph.map(_.executionTime).sum)(Ordering[Double].reverse)

    // Merge crucial-path sub-graphs together
    val crucialPathSubGraphs = sortedSubGraphs.filter(subGraph => subGraph.exists(node => crucialPath.contains(node)))
    val mergedCrucialPathSubGraph = crucialPathSubGraphs.flatten.distinct

    // Merge non-crucial-path sub-graphs together
    val nonCrucialPathSubGraphs = sortedSubGraphs.filter(subGraph => !subGraph.exists(node => crucialPath.contains(node)))
    val mergedNonCrucialPathSubGraph = nonCrucialPathSubGraphs.flatten.distinct

    // Update subGraphs with the merged sub-graphs
    subGraphs.clear()
    subGraphs += mergedCrucialPathSubGraph
    subGraphs += mergedNonCrucialPathSubGraph
  }

  // Function to execute the MDP algorithm
  def executeMDP() {
    // Initialize DAG
    initializeDAG()

    // Perform horizontal merging
    horizontalMerge()

    // Perform vertical merging
    verticalMerge()

    // Place functions within each sub-graph in the pre-selected work-node
    placeFunctionsInWorkNodes()
  }

  private def sharingScore(node1: DAGNode, node2: DAGNode): Double = {
    val totalSize = node1.imageSize + node2.imageSize
    if (totalSize > 0) {
      val sharedSizePercentage = (node1.imageSize min node2.imageSize) / totalSize
      // You may want to store this score in historical data for future use
      sharedSizePercentage
    } else {
      0.0
    }
  }

  // Function to check if the benefit from adding a node to a sub-graph is below a certain threshold
  private def isBenefitBelowThreshold(subGraph: ArrayBuffer[DAGNode], node: DAGNode, threshold: Double): Boolean = {
    // Placeholder for benefit check logic
    // In a real implementation, this could involve calculating the benefit from sharing and checking thresholds.

    // Retrieve historical sharing score based on previous nodes in the sub-graph
    val previousNodes = subGraph.filterNot(_ == node)
    val historicalScores = for {
      node1 <- previousNodes
      node2 <- Seq(node)
      if historicalData.contains((node1.id, node2.id)) || historicalData.contains((node2.id, node1.id))
    } yield historicalData.getOrElse((node1.id, node2.id), historicalData((node2.id, node1.id)))

    // If there is historical data, use the average sharing score; otherwise, use a default value
    val averageScore = if (historicalScores.nonEmpty) historicalScores.sum / historicalScores.length else 0.5

    // Check if the benefit is below the threshold
    (1.0 - averageScore) < threshold
  }

  // Function to place functions within each sub-graph in the pre-selected work-node
  private def placeFunctionsInWorkNodes() {
    // Placeholder for placing functions in work-nodes
    // In a real implementation, this could involve mapping sub-graphs to specific work-nodes.
    // For simplicity, we'll just print the sub-graphs in this example.
    println("Functions placed in work-nodes:")
    subGraphs.zipWithIndex.foreach { case (subGraph, index) =>
      println(s"Work-node $index: ${subGraph.map(_.id).mkString(" -> ")}")
    }
  }
}

import scala.collection.mutable.{ArrayBuffer, Map}

class G_S_P(dagNodes: Seq[DAGNode], dagEdges: Seq[DAGEdge], workNodes: Seq[String], SLO: Double) {
  // Placeholder for historical data
  val historicalData: Map[(Int, Int), Double] = Map()

  // Function to calculate sub-SLO for each step
  private def calculateSubSLO(): Map[DAGNode, Double] = {
    // Placeholder for sub-SLO calculation logic
    // In a real implementation, this would involve calculating weights for each step based on historical data.
    // For simplicity, we'll use a simple calculation here.
    dagNodes.map(node => node -> (node.executionTime / dagNodes.map(_.executionTime).sum * SLO)).toMap
  }

  // Function to calculate sharing score between two nodes based on historical data
  private def sharingScore(node1: DAGNode, node2: DAGNode): Double = {
    historicalData.getOrElse((node1.id, node2.id), 0.0)
  }

  // Function to check if placing a node in a sub-graph exceeds resource pressure limit
  private def isPressureExceeded(subGraph: ArrayBuffer[DAGNode], node: DAGNode): Boolean = {
    // Placeholder for pressure check logic
    // In a real implementation, this would involve checking if adding a node to a sub-graph exceeds a resource limit.
    // For simplicity, we'll use a simple check here.
    subGraph.length >= workNodes.length
  }

  // Function to perform GSP placement
  def placeFunctions(): Unit = {
    val subSLOs = calculateSubSLO()

    // Placeholder for GSP placement logic
    // In a real implementation, this would involve iterating through steps, placing functions, and handling constraints.
    // For simplicity, we'll provide a basic structure here.

    for (node <- dagNodes) {
      // Placeholder for GSP placement logic for each step
      // In a real implementation, this would involve placing functions in work-nodes based on historical data and constraints.
      // For simplicity, we'll just print the steps and sub-SLOs here.
      val subSLO = subSLOs.getOrElse(node, 0.0)
      println(s"Step ${node.id}: Sub-SLO = $subSLO")
    }
  }
}

