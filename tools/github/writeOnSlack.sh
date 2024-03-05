#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

if test -z "$SLACK_WEBHOOK"
then echo "Please create an incoming webhook for slack and set SLACK_WEBHOOK"
     exit 0
fi

echo -n '{"text":' >/tmp/msg$$
echo -n "$@" | jq -Rsa . >>/tmp/msg$$
echo -n '}' >>/tmp/msg$$

curl -X POST -H 'Content-type: application/json' --data "@/tmp/msg$$" "$SLACK_WEBHOOK"
