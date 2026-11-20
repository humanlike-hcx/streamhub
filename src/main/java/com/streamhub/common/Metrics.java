package com.streamhub.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 进程内计数器，GET /metrics 可以看。压测报告里的"写次数""落库行数"都从这里读。
 * LongAdder：多线程同时 +1 时比 AtomicLong 竞争小。
 */
@Component
public class Metrics {
    // 入口
    public final LongAdder received = new LongAdder();      // 收到的 send 请求
    public final LongAdder acked = new LongAdder();         // MQ 确认后回了 ack
    public final LongAdder invalid = new LongAdder();
    public final LongAdder rateLimited = new LongAdder();
    public final LongAdder busy = new LongAdder();          // 在途满 / workerId 失效 / MQ 失败
    public final LongAdder videoClosed = new LongAdder();
    // 推送
    public final LongAdder broadcastReceived = new LongAdder(); // 广播消费收到的消息
    public final LongAdder staleDropped = new LongAdder();      // 太旧不推（比如节点重启后的积压）
    public final LongAdder pushFrames = new LongAdder();        // 分片 flush 次数（每次生成一个合并帧）
    public final LongAdder channelWrites = new LongAdder();     // 真正 writeAndFlush 到连接的次数
    public final LongAdder slowSkipped = new LongAdder();       // 连接写不动被跳过的次数
    // 落库
    public final LongAdder persistBatches = new LongAdder();
    public final LongAdder persistMessages = new LongAdder();
    public final LongAdder persistInserted = new LongAdder();   // 真正插入的行（重复的不算）
    public final LongAdder persistFailures = new LongAdder();
    public final LongAdder persistNanos = new LongAdder();

    public Map<String, Long> snapshot() {
        var m = new LinkedHashMap<String, Long>();
        m.put("received", received.sum());
        m.put("acked", acked.sum());
        m.put("invalid", invalid.sum());
        m.put("rateLimited", rateLimited.sum());
        m.put("busy", busy.sum());
        m.put("videoClosed", videoClosed.sum());
        m.put("broadcastReceived", broadcastReceived.sum());
        m.put("staleDropped", staleDropped.sum());
        m.put("pushFrames", pushFrames.sum());
        m.put("channelWrites", channelWrites.sum());
        m.put("slowSkipped", slowSkipped.sum());
        m.put("persistBatches", persistBatches.sum());
        m.put("persistMessages", persistMessages.sum());
        m.put("persistInserted", persistInserted.sum());
        m.put("persistFailures", persistFailures.sum());
        m.put("persistNanos", persistNanos.sum());
        return m;
    }
}
