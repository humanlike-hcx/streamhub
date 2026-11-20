#!/bin/bash
# lt.sh <label> [ltclient 参数...] → results/<label>.json，并打印一行摘要
cd "$(dirname "$0")"
label=$1; shift
PIDS=$(pgrep -f 'streamhub.node.name=' | paste -sd,)
./client/ltclient -pids "$PIDS" -label "$label" "$@" > results/$label.json 2> results/$label.err
python3 - "$label" <<'PY'
import json,sys
r=json.load(open(f"results/{sys.argv[1]}.json")); d=r["server_metrics_delta"]
def f(x): return round(x,2) if isinstance(x,float) else x
keys=["label","nodes","receivers","senders","target_rate","connect_ok","connect_fail","sent","achieved_rate","acked","err_busy","err_rate_limited","read_errors(disconnects)","delivery_ratio_per_node","push_p50_ms","push_p99_ms","push_max_ms","ack_p50_ms","ack_p99_ms","items_per_frame","server_cpu_cores"]
print(" ".join(f"{k}={f(r.get(k)) if not isinstance(r.get(k),list) else [round(x,4) for x in r[k]]}" for k in keys if k in r),
      f"channelWrites={int(d.get('channelWrites',0))} slowSkipped={int(d.get('slowSkipped',0))} persistInserted={int(d.get('persistInserted',0))}")
PY
