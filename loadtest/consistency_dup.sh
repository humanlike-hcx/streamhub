#!/bin/bash
# 重复投递一致性：往 MQ 里直接塞 N 条不同 id 的弹幕、每条投 3 次，等落库消费完后核对
# 期望：表里行数 = 不同 id 数 = N = video_danmaku_stat.count（重复的被 INSERT IGNORE 挡掉，计数不多加）
# 用法：consistency_dup.sh <run> [N]
cd "$(dirname "$0")"
run=$1; N=${2:-20000}; vid=$((900000+run)); base=$((1000000000000000*run))
MYSQL="/workspace/dl/mysql-8.4.5-linux-glibc2.28-x86_64-minimal/bin/mysql -uroot -S /tmp/mysql.sock -N streamhub_local"
$MYSQL -e "DELETE FROM danmaku WHERE video_id=$vid; DELETE FROM video_danmaku_stat WHERE video_id=$vid;"
m0=$(curl -s localhost:8080/metrics)
java -cp "$(cat tools/cp.txt)" tools/MqInjector.java 127.0.0.1:9876 danmaku $vid $base $N 3
# 等落库追平：连续 3 秒行数不变就算消费完
prev=-1; for i in $(seq 1 120); do c=$($MYSQL -e "SELECT COUNT(*) FROM danmaku WHERE video_id=$vid"); [ "$c" = "$prev" ] && [ "$c" -ge "$N" ] && break; prev=$c; sleep 3; done
m1=$(curl -s localhost:8080/metrics)
$MYSQL -e "SELECT 'rows',COUNT(*),'distinct',COUNT(DISTINCT id),'stat',(SELECT count FROM video_danmaku_stat WHERE video_id=$vid) FROM danmaku WHERE video_id=$vid" | tr '\t' ' ' | sed "s/^/run=$run expected=$N /"
python3 -c "
import json,sys; a=json.loads('''$m0'''); b=json.loads('''$m1''')
print('persistMessages_delta',b['persistMessages']-a['persistMessages'],'persistInserted_delta',b['persistInserted']-a['persistInserted'],'persistFailures_delta',b['persistFailures']-a['persistFailures'])"
