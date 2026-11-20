#!/bin/bash
# 启动一个实例：run_node.sh <name> <httpPort> <wsPort> [其他环境变量...]
# 例：./loadtest/run_node.sh a 8080 9100 MERGE_WINDOW_MS=0
cd "$(dirname "$0")/.."
name=$1; http=$2; ws=$3; shift 3
pkill -f "streamhub.node.name=$name" ; sleep 1
env SERVER_PORT=$http WS_PORT=$ws STREAMHUB_NODE_ID=node-$name "$@" \
  nohup java -Dstreamhub.node.name=$name -Xms${HEAP:-768m} -Xmx${HEAP:-768m} -XX:+UseG1GC \
  -jar target/streamhub-0.1.0-SNAPSHOT.jar --spring.profiles.active=local > loadtest/node-$name.log 2>&1 &
for i in $(seq 1 60); do
  curl -sf localhost:$http/health >/dev/null && { echo "node $name up http=$http ws=$ws pid=$(pgrep -f "streamhub.node.name=$name")"; exit 0; }
  sleep 1
done
echo "node $name failed"; tail -40 loadtest/node-$name.log; exit 1
