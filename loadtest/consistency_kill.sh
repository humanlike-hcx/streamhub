#!/bin/bash
# kill -9 一致性：边压边把节点 kill -9，再拉起来，核对"已 ack 的弹幕一条不丢、库里不重复、计数准确"
# 用法：consistency_kill.sh <run>   （房间/视频用 7，测前清空）
cd "$(dirname "$0")"
run=$1; vid=7
MYSQL="/workspace/dl/mysql-8.4.5-linux-glibc2.28-x86_64-minimal/bin/mysql -uroot -S /tmp/mysql.sock -N streamhub_local"
$MYSQL -e "DELETE FROM danmaku WHERE video_id=$vid; DELETE FROM video_danmaku_stat WHERE video_id=$vid;"
acks=results/kill_run$run.acks
./client/ltclient -room $vid -receivers 200 -sampled 20 -rate 1000 -duration 25s -drain 2s -acks $acks -label kill_run$run > results/kill_run$run.json 2> results/kill_run$run.err &
cpid=$!
sleep 12
echo "kill -9 at $(date +%T)"; pkill -9 -f 'streamhub.node.name=a'
sleep 2
./run_node.sh a 8080 9100
wait $cpid
# 等落库追平
prev=-1; for i in $(seq 1 60); do c=$($MYSQL -e "SELECT COUNT(*) FROM danmaku WHERE video_id=$vid"); [ "$c" = "$prev" ] && break; prev=$c; sleep 3; done
sort -u $acks > /tmp/acked.txt
$MYSQL -e "SELECT id FROM danmaku WHERE video_id=$vid" | sort -u > /tmp/db.txt
echo "run=$run acked=$(wc -l < /tmp/acked.txt) db_rows=$($MYSQL -e "SELECT COUNT(*) FROM danmaku WHERE video_id=$vid") db_distinct=$(wc -l < /tmp/db.txt) stat=$($MYSQL -e "SELECT count FROM video_danmaku_stat WHERE video_id=$vid") acked_missing_in_db=$(comm -23 /tmp/acked.txt /tmp/db.txt | wc -l) in_db_not_acked=$(comm -13 /tmp/acked.txt /tmp/db.txt | wc -l)"
echo "after-restart metrics: $(curl -s localhost:8080/metrics | python3 -c 'import json,sys;m=json.load(sys.stdin);print({k:m[k] for k in ["persistMessages","persistInserted","persistFailures"]})')"
