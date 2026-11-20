package com.streamhub.room;

import com.streamhub.common.Metrics;
import com.streamhub.common.StreamhubProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 一个房间在"一个 EventLoop 线程"上的那部分观众。
 *
 * 为什么按 EventLoop 分片：Netty 里每个连接终身绑定一个 EventLoop 线程。
 * 把同一线程上的连接放进同一个分片、分片的所有操作都在这个线程里执行，
 * 就天然没有并发，不需要锁；不同分片在不同线程上并行推送。
 * （EchoFrame 是一个调度线程拿着房间锁给 4 万个连接挨个写，所以大房间延迟飙升。）
 *
 * 除构造外，所有方法都只能在 loop 线程里调用。
 */
final class RoomShard {
    private static final String PREFIX = "{\"op\":\"batch\",\"items\":[";
    private static final String SUFFIX = "]}";
    private static final int ENVELOPE = PREFIX.length() + SUFFIX.length();

    final EventLoop loop;
    private final StreamhubProperties.Room cfg;
    private final Metrics metrics;
    private final Set<Channel> channels = new LinkedHashSet<>();
    private final List<String> pending = new ArrayList<>();
    private int pendingBytes = ENVELOPE;
    private ScheduledFuture<?> timer;

    RoomShard(EventLoop loop, StreamhubProperties.Room cfg, Metrics metrics) {
        this.loop = loop;
        this.cfg = cfg;
        this.metrics = metrics;
    }

    void add(Channel ch) {
        channels.add(ch);
    }

    void remove(Channel ch) {
        channels.remove(ch);
    }

    int size() {
        return channels.size();
    }

    /**
     * 收到一条要推的弹幕（已经是 JSON 字符串）。固定窗口合并（决策 5A）：
     * - 攒够 maxItems 条，或者再加这条会超过 maxFrameBytes → 立刻推
     * - 否则第一条进来时开一个 windowMs 的定时器，到点把攒的一起推
     * - windowMs=0 表示关闭合并，每条立刻推（压测对比用）
     */
    void append(String itemJson) {
        int bytes = ByteBufUtil.utf8Bytes(itemJson) + 1;   // +1 是逗号
        if (!pending.isEmpty() && pendingBytes + bytes > cfg.maxFrameBytes()) {
            flush();
        }
        pending.add(itemJson);
        pendingBytes += bytes;
        if (cfg.windowMs() <= 0 || pending.size() >= cfg.maxItems()) {
            flush();
        } else if (timer == null) {
            timer = loop.schedule(this::onTimer, cfg.windowMs(), TimeUnit.MILLISECONDS);
        }
    }

    private void onTimer() {
        timer = null;
        flush();
    }

    void flush() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        if (pending.isEmpty()) {
            return;
        }
        String frame = PREFIX + String.join(",", pending) + SUFFIX;
        pending.clear();
        pendingBytes = ENVELOPE;
        metrics.pushFrames.increment();
        writeToAll(frame);
    }

    /** 不经过合并、直接推（在线人数这类控制消息）。 */
    void writeToAll(String text) {
        if (channels.isEmpty()) {
            return;
        }
        // 只编码一次：所有连接共享同一块内存（retainedDuplicate 只加引用计数，不复制）
        ByteBuf shared = ByteBufUtil.writeUtf8(channels.iterator().next().alloc(), text);
        try {
            for (Channel ch : channels) {
                // 慢客户端：发送缓冲区超过高水位就跳过这一帧，不让一个人拖慢整个房间，也防止内存堆积
                if (!ch.isWritable()) {
                    metrics.slowSkipped.increment();
                    continue;
                }
                ch.writeAndFlush(new TextWebSocketFrame(shared.retainedDuplicate()), ch.voidPromise());
                metrics.channelWrites.increment();
            }
        } finally {
            shared.release();
        }
    }
}
