#!/bin/bash
# 两节点：观众和发送者轮流连 a / b，验证跨节点投递（b 上发的弹幕 a 上的观众也收到）、两边 workerId 不同、id 不重复、落库一致
# 用法：two_node.sh <run> [rate]
cd "$(dirname "$0")"
run=$1; rate=${2:-1000}; vid=3
MYSQL="/workspace/dl/mysql-8.4.5-linux-glibc2.28-x86_64-minimal/bin/mysql -uroot -S /tmp/mysql.sock -N streamhub_local"
$MYSQL -e "DELETE FROM danmaku WHERE video_id=$vid; DELETE FROM video_danmaku_stat WHERE video_id=$vid;"
./lt.sh two_node_run$run -room $vid -ws ws://127.0.0.1:9100/ws/room/,ws://127.0.0.1:9101/ws/room/ \
  -metrics http://127.0.0.1:8080/metrics,http://127.0.0.1:8081/metrics -receivers 2000 -sampled 50 -rate $rate -duration 20s -acks results/two_node_run$run.acks
sleep 3
python3 - $run <<'PY'
import json,sys; r=json.load(open(f"results/two_node_run{sys.argv[1]}.json"))
print({k:r.get(k) for k in ["acked","distinct_ack_ids","ack_ids_by_worker","delivery_ratio_per_node"]})
PY
sort -u results/two_node_run$run.acks > /tmp/acked.txt; $MYSQL -e "SELECT id FROM danmaku WHERE video_id=$vid" | sort -u > /tmp/db.txt
echo "db_rows=$($MYSQL -e "SELECT COUNT(*) FROM danmaku WHERE video_id=$vid") db_distinct=$(wc -l < /tmp/db.txt) stat=$($MYSQL -e "SELECT count FROM video_danmaku_stat WHERE video_id=$vid") acked_missing_in_db=$(comm -23 /tmp/acked.txt /tmp/db.txt | wc -l)"
for p in 8080 8081; do curl -s localhost:$p/metrics | python3 -c 'import json,sys;m=json.load(sys.stdin);print(m["nodeId"],"workerId",m["workerId"],"received",m["received"],"broadcastReceived",m["broadcastReceived"],"persistInserted",m["persistInserted"])'; done
