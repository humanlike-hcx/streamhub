package com.streamhub.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.streamhub.common.Metrics;
import com.streamhub.common.Protocol;
import com.streamhub.common.StreamhubProperties;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * EmbeddedChannel：Netty 自带的"假连接"，写出去的数据留在内存里可以直接读，
 * 定时任务可以手动拨时间，测试合并窗口不用真的 sleep。
 */
class RoomRegistryTest {
    private final Metrics metrics = new Metrics();

    private RoomRegistry registry(int windowMs, int maxItems) {
        return new RoomRegistry(new StreamhubProperties.Room(windowMs, maxItems, 16384), metrics);
    }

    private static String item(int i) {
        return "{\"id\":\"" + i + "\"}";
    }

    private static JsonNode readFrame(EmbeddedChannel ch) throws Exception {
        TextWebSocketFrame f = ch.readOutbound();
        if (f == null) {
            return null;
        }
        try {
            return Protocol.JSON.readTree(f.text());
        } finally {
            f.release();
        }
    }

    @Test
    void flushesImmediatelyWhenBatchIsFull() throws Exception {
        var rooms = registry(100, 50);
        var ch = new EmbeddedChannel();
        rooms.join(1, ch);
        for (int i = 0; i < 49; i++) {
            rooms.publish(1, item(i));
        }
        ch.runPendingTasks();
        assertThat(readFrame(ch)).isNull();               // 49 条：还在攒
        rooms.publish(1, item(49));
        ch.runPendingTasks();
        JsonNode frame = readFrame(ch);
        assertThat(frame.get("op").asText()).isEqualTo("batch");
        assertThat(frame.get("items")).hasSize(50);       // 第 50 条触发立即推送
    }

    @Test
    void flushesWhenWindowExpires() throws Exception {
        var rooms = registry(100, 50);
        var ch = new EmbeddedChannel();
        ch.freezeTime();
        rooms.join(1, ch);
        rooms.publish(1, item(1));
        rooms.publish(1, item(2));
        ch.runPendingTasks();
        assertThat(readFrame(ch)).isNull();
        ch.advanceTimeBy(100, TimeUnit.MILLISECONDS);
        ch.runPendingTasks();
        assertThat(readFrame(ch).get("items")).hasSize(2);
    }

    @Test
    void windowZeroMeansNoMerging() throws Exception {
        var rooms = registry(0, 50);
        var ch = new EmbeddedChannel();
        rooms.join(1, ch);
        rooms.publish(1, item(1));
        rooms.publish(1, item(2));
        ch.runPendingTasks();
        assertThat(readFrame(ch).get("items")).hasSize(1);
        assertThat(readFrame(ch).get("items")).hasSize(1);
    }

    @Test
    void eachEventLoopShardReceivesTheBroadcast() throws Exception {
        var rooms = registry(0, 50);
        var a = new EmbeddedChannel();      // 两个 EmbeddedChannel 各有自己的 EventLoop = 两个分片
        var b = new EmbeddedChannel();
        rooms.join(7, a);
        rooms.join(7, b);
        rooms.publish(7, item(1));
        a.runPendingTasks();
        b.runPendingTasks();
        assertThat(readFrame(a)).isNotNull();
        assertThat(readFrame(b)).isNotNull();
        assertThat(rooms.roomCount()).isEqualTo(1);
    }

    @Test
    void slowClientIsSkippedNotBlocking() throws Exception {
        var rooms = registry(0, 50);
        var slow = new EmbeddedChannel();
        rooms.join(1, slow);
        // 模拟发送缓冲已满：isWritable() 变成 false
        slow.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        rooms.publish(1, item(1));
        slow.runPendingTasks();
        assertThat(readFrame(slow)).isNull();
        assertThat(metrics.slowSkipped.sum()).isEqualTo(1);
    }

    @Test
    void roomIsRemovedWhenLastViewerLeaves() {
        var rooms = registry(100, 50);
        var ch = new EmbeddedChannel();
        rooms.join(3, ch);
        assertThat(rooms.roomCount()).isEqualTo(1);
        rooms.leave(3, ch);
        assertThat(rooms.roomCount()).isZero();
        assertThat(rooms.connectionCount()).isZero();
    }
}
