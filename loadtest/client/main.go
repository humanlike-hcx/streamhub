// Streamhub 压测客户端。
// 协议：连 ws://host:port/ws/room/{videoId}；发送者先发 {"op":"auth","token":...}，
// 再发 {"op":"send","clientSeq":n,"content":"lt:<发送时刻ns>:...","playbackTime":x}；
// 服务端回 ack/error，并向房间推 {"op":"batch","items":[...]}。
// 延迟：content 里带发送时刻，抽样观众收到后用当前时刻相减（客户端和服务端同机，同一时钟）。
// 多节点：-ws 传逗号分隔的多个地址，观众和发送者轮流分配到各节点。
package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

var (
	wsList     = flag.String("ws", "ws://127.0.0.1:9100/ws/room/", "逗号分隔的 WebSocket 前缀")
	metricList = flag.String("metrics", "http://127.0.0.1:8080/metrics", "逗号分隔的 /metrics 地址（与 -ws 对应）")
	pidList    = flag.String("pids", "", "逗号分隔的服务端 pid，用于统计 CPU")
	room       = flag.Int64("room", 1, "视频 id")
	receivers  = flag.Int("receivers", 1000, "纯观众连接数")
	sampled    = flag.Int("sampled", 50, "每个节点上解析每条消息算延迟的观众数")
	senders    = flag.Int("senders", 0, "发送者连接数；0 = rate/perSender")
	perSender  = flag.Float64("persender", 4, "每个发送者每秒条数（服务端限流 5/s）")
	rate       = flag.Float64("rate", 100, "总发送速率 条/秒")
	duration   = flag.Duration("duration", 20*time.Second, "")
	drain      = flag.Duration("drain", 3*time.Second, "停止发送后再等多久")
	connPar    = flag.Int("connpar", 100, "并发建连数")
	userBase   = flag.Int64("userbase", 1, "发送者 userId 起始值")
	secretB64  = flag.String("secret", "ZGV2LW9ubHktc2VjcmV0LWNoYW5nZS1tZS1pbi1wcm9kdWN0aW9uLTAwMDAwMDAwMDA=", "JWT 密钥 base64")
	label      = flag.String("label", "", "")
	acksOut    = flag.String("acks", "", "把所有 ack 的弹幕 id 写到这个文件")
	hold       = flag.Bool("hold", false, "只建连不发送（测纯连接容量）")
)

type latRec struct {
	mu sync.Mutex
	v  []int64
}

func (l *latRec) add(x int64) { l.mu.Lock(); l.v = append(l.v, x); l.mu.Unlock() }
func (l *latRec) sorted() []int64 {
	l.mu.Lock()
	v := append([]int64(nil), l.v...)
	l.mu.Unlock()
	sort.Slice(v, func(a, b int) bool { return v[a] < v[b] })
	return v
}

var (
	urls                                                  []string
	connOK, connFail, sent, writeErr, acked, readErr      atomic.Int64
	errBusy, errRate, errOther, framesBatch, itemsAll     atomic.Int64
	authFail                                              atomic.Int64
	measuring                                             atomic.Bool
	pushLat, ackLat                                       latRec
	sampledItemsPerNode                                   []atomic.Int64
	sampledCountPerNode                                   []int64
	ackIDs                                                sync.Map
	sendTimes                                             sync.Map // key: senderIdx<<32|seq
)

func jwt(sub string, key []byte) string {
	enc := base64.RawURLEncoding
	h := enc.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	now := time.Now().Unix()
	p := enc.EncodeToString([]byte(fmt.Sprintf(`{"sub":"%s","iat":%d,"exp":%d}`, sub, now, now+86400)))
	m := hmac.New(sha256.New, key)
	m.Write([]byte(h + "." + p))
	return h + "." + p + "." + enc.EncodeToString(m.Sum(nil))
}

func dial(node int) (*websocket.Conn, error) {
	d := websocket.Dialer{HandshakeTimeout: 15 * time.Second, ReadBufferSize: 4096, WriteBufferSize: 1024}
	c, _, err := d.Dial(urls[node]+strconv.FormatInt(*room, 10), nil)
	return c, err
}

var batchPrefix = []byte(`{"op":"batch"`)
var sentAtKey = []byte(`"sentAt"`)

type item struct {
	Content string `json:"content"`
}
type batch struct {
	Items []item `json:"items"`
}

func viewer(c *websocket.Conn, node int, sample bool) {
	for {
		_, msg, err := c.ReadMessage()
		if err != nil {
			readErr.Add(1)
			return
		}
		if !bytes.HasPrefix(msg, batchPrefix) {
			continue
		}
		now := time.Now().UnixNano()
		framesBatch.Add(1)
		itemsAll.Add(int64(bytes.Count(msg, sentAtKey)))
		if !sample || !measuring.Load() {
			continue
		}
		var b batch
		if json.Unmarshal(msg, &b) != nil {
			continue
		}
		for _, it := range b.Items {
			p := strings.Split(it.Content, ":")
			if len(p) >= 2 && p[0] == "lt" {
				if ts, e := strconv.ParseInt(p[1], 10, 64); e == nil {
					pushLat.add(now - ts)
					sampledItemsPerNode[node].Add(1)
				}
			}
		}
	}
}

type reply struct {
	Op        string `json:"op"`
	ClientSeq int64  `json:"clientSeq"`
	ID        string `json:"id"`
	Code      string `json:"code"`
}

// 发送者的读循环：处理 ack / error（它也在房间里，会收到 batch，只计数不解析）
func senderReader(c *websocket.Conn, idx int, authed chan<- bool) {
	first := true
	for {
		_, msg, err := c.ReadMessage()
		if err != nil {
			readErr.Add(1)
			if first {
				authed <- false
			}
			return
		}
		if bytes.HasPrefix(msg, batchPrefix) || bytes.HasPrefix(msg, []byte(`{"op":"online"`)) {
			continue
		}
		var r reply
		if json.Unmarshal(msg, &r) != nil {
			continue
		}
		switch r.Op {
		case "authOk":
			if first {
				first = false
				authed <- true
			}
		case "ack":
			acked.Add(1)
			if *acksOut != "" {
				ackIDs.Store(r.ID, true)
			}
			if t, ok := sendTimes.LoadAndDelete(int64(idx)<<32 | r.ClientSeq); ok && measuring.Load() {
				ackLat.add(time.Now().UnixNano() - t.(int64))
			}
		case "error":
			sendTimes.Delete(int64(idx)<<32 | r.ClientSeq)
			switch r.Code {
			case "BUSY":
				errBusy.Add(1)
			case "RATE_LIMITED":
				errRate.Add(1)
			case "UNAUTHORIZED":
				authFail.Add(1)
				if first {
					first = false
					authed <- false
				}
			default:
				errOther.Add(1)
			}
		}
	}
}

func split(s string) []string {
	if s == "" {
		return nil
	}
	return strings.Split(s, ",")
}

func procCPU(pid string) float64 {
	b, err := os.ReadFile("/proc/" + pid + "/stat")
	if err != nil {
		return 0
	}
	s := string(b)
	f := strings.Fields(s[strings.LastIndex(s, ")")+2:])
	u, _ := strconv.ParseFloat(f[11], 64)
	st, _ := strconv.ParseFloat(f[12], 64)
	return (u + st) / 100.0
}

func metrics(u string) map[string]float64 {
	m := map[string]float64{}
	r, err := http.Get(u)
	if err != nil {
		return m
	}
	defer r.Body.Close()
	b, _ := io.ReadAll(r.Body)
	var raw map[string]any
	json.Unmarshal(b, &raw)
	for k, v := range raw {
		if f, ok := v.(float64); ok {
			m[k] = f
		}
	}
	return m
}

func pct(v []int64, p float64) float64 {
	if len(v) == 0 {
		return -1
	}
	return float64(v[int(float64(len(v)-1)*p)]) / 1e6
}

func main() {
	flag.Parse()
	urls = split(*wsList)
	nodes := len(urls)
	sampledItemsPerNode = make([]atomic.Int64, nodes)
	sampledCountPerNode = make([]int64, nodes)
	nSenders := *senders
	if nSenders == 0 && !*hold {
		nSenders = int(*rate / *perSender + 0.999)
	}
	res := map[string]any{"label": *label, "nodes": nodes, "room": *room, "receivers": *receivers, "senders": nSenders,
		"target_rate": *rate, "duration_s": duration.Seconds()}

	// 1. 观众建连（并发 connPar），第 i 个连到第 i%nodes 个节点
	t0 := time.Now()
	var mu sync.Mutex
	conns := []*websocket.Conn{}
	sem := make(chan struct{}, *connPar)
	var wg sync.WaitGroup
	perNodeIdx := make([]atomic.Int64, nodes)
	for i := 0; i < *receivers; i++ {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int) {
			defer wg.Done()
			defer func() { <-sem }()
			node := i % nodes
			c, err := dial(node)
			if err != nil {
				connFail.Add(1)
				return
			}
			connOK.Add(1)
			k := perNodeIdx[node].Add(1)
			sample := k <= int64(*sampled)
			mu.Lock()
			conns = append(conns, c)
			if sample {
				sampledCountPerNode[node]++
			}
			mu.Unlock()
			go viewer(c, node, sample)
		}(i)
	}
	wg.Wait()
	res["connect_ok"] = connOK.Load()
	res["connect_fail"] = connFail.Load()
	res["connect_secs"] = time.Since(t0).Seconds()

	// 2. 发送者建连并认证
	key, _ := base64.StdEncoding.DecodeString(*secretB64)
	sconns := make([]*websocket.Conn, nSenders)
	for i := 0; i < nSenders; i++ {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int) {
			defer wg.Done()
			defer func() { <-sem }()
			c, err := dial(i % nodes)
			if err != nil {
				connFail.Add(1)
				return
			}
			authed := make(chan bool, 1)
			go senderReader(c, i, authed)
			tok := jwt(strconv.FormatInt(*userBase+int64(i), 10), key)
			c.WriteMessage(websocket.TextMessage, []byte(`{"op":"auth","token":"`+tok+`"}`))
			select {
			case ok := <-authed:
				if ok {
					sconns[i] = c
				}
			case <-time.After(10 * time.Second):
			}
		}(i)
	}
	wg.Wait()
	ready := 0
	for _, c := range sconns {
		if c != nil {
			ready++
		}
	}
	res["senders_authed"] = ready
	time.Sleep(2 * time.Second)

	pids := split(*pidList)
	cpu0 := 0.0
	for _, p := range pids {
		cpu0 += procCPU(p)
	}
	murls := split(*metricList)
	m0 := []map[string]float64{}
	for _, u := range murls {
		m0 = append(m0, metrics(u))
	}

	if *hold {
		time.Sleep(*duration)
	} else {
		measuring.Store(true)
		start := time.Now()
		var swg sync.WaitGroup
		per := *rate / float64(ready)
		interval := time.Duration(float64(time.Second) / per)
		for i, c := range sconns {
			if c == nil {
				continue
			}
			swg.Add(1)
			go func(i int, c *websocket.Conn) {
				defer swg.Done()
				// 把各发送者的起始时刻均匀错开到一个发送间隔内，避免所有人同一时刻齐发造成突发
				next := start.Add(time.Duration(i) * interval / time.Duration(len(sconns)))
				uid := *userBase + int64(i)
				for seq := int64(1); time.Since(start) < *duration; seq++ {
					if d := time.Until(next); d > 0 {
						time.Sleep(d)
					}
					next = next.Add(interval)
					now := time.Now().UnixNano()
					sendTimes.Store(int64(i)<<32|seq, now)
					msg := fmt.Sprintf(`{"op":"send","clientSeq":%d,"content":"lt:%d:%d:%d","color":"#ffffff","playbackTime":%d.5}`,
						seq, now, uid, seq, seq%600)
					if err := c.WriteMessage(websocket.TextMessage, []byte(msg)); err != nil {
						writeErr.Add(1)
						return
					}
					sent.Add(1)
				}
			}(i, c)
		}
		swg.Wait()
		res["send_secs"] = time.Since(start).Seconds()
		time.Sleep(*drain)
		measuring.Store(false)
	}
	elapsed := time.Since(t0).Seconds() // 仅作参考
	_ = elapsed
	window := duration.Seconds() + drain.Seconds()
	if *hold {
		window = duration.Seconds()
	}
	cpu1 := 0.0
	for _, p := range pids {
		cpu1 += procCPU(p)
	}
	delta := map[string]float64{}
	for i, u := range murls {
		m1 := metrics(u)
		for k, v := range m1 {
			delta[k] += v - m0[i][k]
		}
		if i == 0 {
			res["node0_connections"] = m1["connections"]
		}
	}
	s := sent.Load()
	res["sent"] = s
	res["achieved_rate"] = float64(s) / duration.Seconds()
	res["acked"] = acked.Load()
	res["err_busy"] = errBusy.Load()
	res["err_rate_limited"] = errRate.Load()
	res["err_other"] = errOther.Load()
	res["auth_fail"] = authFail.Load()
	res["write_errors"] = writeErr.Load()
	res["read_errors(disconnects)"] = readErr.Load()
	pl := pushLat.sorted()
	al := ackLat.sorted()
	res["push_p50_ms"], res["push_p99_ms"], res["push_max_ms"] = pct(pl, .5), pct(pl, .99), pct(pl, 1)
	res["ack_p50_ms"], res["ack_p99_ms"] = pct(al, .5), pct(al, .99)
	ratios := []float64{}
	for n := 0; n < nodes; n++ {
		if sampledCountPerNode[n] > 0 && acked.Load() > 0 {
			// 每个抽样观众应该收到每一条被 ack 的弹幕（不管从哪个节点发出）
			ratios = append(ratios, float64(sampledItemsPerNode[n].Load())/float64(acked.Load()*sampledCountPerNode[n]))
		}
	}
	res["delivery_ratio_per_node"] = ratios
	res["client_batch_frames"] = framesBatch.Load()
	res["items_per_frame"] = float64(itemsAll.Load()) / float64(max(framesBatch.Load(), 1))
	res["server_metrics_delta"] = delta
	if len(pids) > 0 {
		res["server_cpu_cores"] = (cpu1 - cpu0) / window
	}
	if *acksOut != "" {
		f, _ := os.Create(*acksOut)
		n := 0
		workers := map[int64]int{}
		ackIDs.Range(func(k, _ any) bool {
			fmt.Fprintln(f, k)
			n++
			if id, e := strconv.ParseInt(k.(string), 10, 64); e == nil {
				workers[(id>>12)&1023]++
			}
			return true
		})
		f.Close()
		res["distinct_ack_ids"] = n
		res["ack_ids_by_worker"] = workers
	}
	out, _ := json.MarshalIndent(res, "", " ")
	fmt.Println(string(out))
	for _, c := range conns {
		c.Close()
	}
	for _, c := range sconns {
		if c != nil {
			c.Close()
		}
	}
}
