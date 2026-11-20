#!/bin/bash
# 落库吞吐：先停节点，往 MQ 里堆 N 条积压，再启动节点，看落库消费者多快把积压写进 MySQL
# 用法：drain.sh <run> [N]
cd "$(dirname "$0")"
run=$1; N=${2:-500000}; vid=$((900010+run)); base=$((1000000000000000*(10+run)))
MYSQL="/workspace/dl/mysql-8.4.5-linux-glibc2.28-x86_64-minimal/bin/mysql -uroot -S /tmp/mysql.sock -N streamhub_local"
pkill -f 'streamhub.node.name=a'; sleep 3
$MYSQL -e "DELETE FROM danmaku WHERE video_id=$vid; DELETE FROM video_danmaku_stat WHERE video_id=$vid;"
java -cp "$(cat tools/cp.txt)" tools/MqInjector.java 127.0.0.1:9876 danmaku $vid $base $N 1 2>&1 | grep -v WARN
t0=$(date +%s.%N); first=""
./run_node.sh a 8080 9100 >/dev/null &   # 后台启动，立刻开始轮询，避免漏掉启动阶段已经开始的消费
while true; do
  c=$($MYSQL -e "SELECT COUNT(*) FROM danmaku WHERE video_id=$vid")
  [ -z "$first" ] && [ "$c" -gt 0 ] && first=$(date +%s.%N)
  [ "$c" -ge "$N" ] && break
  sleep 0.2
done
t1=$(date +%s.%N)
m=$(curl -s localhost:8080/metrics)
python3 - "$t0" "$first" "$t1" "$N" "$m" "$run" <<'PY'
import sys,json
t0,f,t1,n=float(sys.argv[1]),float(sys.argv[2]),float(sys.argv[3]),int(sys.argv[4]); m=json.loads(sys.argv[5])
avg_batch=m['persistMessages']/max(m['persistBatches'],1)
print(f"run={sys.argv[6]} backlog={n} drain_seconds(first_row->all)={t1-f:.2f} rows_per_sec={n/(t1-f):.0f} since_java_start={t1-t0:.2f}s avg_batch={avg_batch:.1f} persistFailures={m['persistFailures']} db_ms_per_batch={m['persistNanos']/1e6/max(m['persistBatches'],1):.1f}")
PY
echo "stat=$($MYSQL -e "SELECT count FROM video_danmaku_stat WHERE video_id=$vid") distinct=$($MYSQL -e "SELECT COUNT(DISTINCT id) FROM danmaku WHERE video_id=$vid")"
