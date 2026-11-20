package com.streamhub.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.streamhub.common.DanmakuEvent;
import com.streamhub.common.Metrics;
import com.streamhub.common.Protocol;
import com.streamhub.common.StreamhubProperties;
import com.streamhub.common.id.SnowflakeIdGenerator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IngestServiceTest {
    /** 假的 MQ：先把回调存起来，测试里决定什么时候"成功"或"失败"。 */
    static final class HeldPublisher implements DanmakuPublisher {
        final List<DanmakuEvent> events = new ArrayList<>();
        final List<Runnable> successes = new ArrayList<>();
        final List<Consumer<Throwable>> failures = new ArrayList<>();

        @Override
        public void publish(DanmakuEvent e, Runnable ok, Consumer<Throwable> fail) {
            events.add(e);
            successes.add(ok);
            failures.add(fail);
        }
    }

    private final Metrics metrics = new Metrics();
    private final HeldPublisher mq = new HeldPublisher();
    private final EmbeddedChannel ch = new EmbeddedChannel();

    private IngestService service(int maxInFlight, int burst, boolean videoOpen) {
        var cfg = new StreamhubProperties.Ingest(maxInFlight, 0.0001, burst, 30);
        VideoGate gate = id -> CompletableFuture.completedFuture(videoOpen);
        var ids = new SnowflakeIdGenerator(() -> 1, System::currentTimeMillis);
        return new IngestService(gate, mq, ids, metrics, cfg, () -> 0L, System::currentTimeMillis);
    }

    private static SendRequest req(long seq) {
        return new SendRequest(seq, "前方高能", "#ff0000", 12.5);
    }

    private JsonNode reply() throws Exception {
        TextWebSocketFrame f = ch.readOutbound();
        try {
            return Protocol.JSON.readTree(f.text());
        } finally {
            f.release();
        }
    }

    @Test
    void acksOnlyAfterMqConfirms() throws Exception {
        var svc = service(10, 10, true);
        svc.submit(ch, 5, 100, req(1));
        assertThat((Object) ch.readOutbound()).isNull();       // MQ 还没确认，不回 ack
        assertThat(mq.events.get(0).videoId()).isEqualTo(100);
        mq.successes.get(0).run();
        JsonNode ack = reply();
        assertThat(ack.get("op").asText()).isEqualTo("ack");
        assertThat(ack.get("clientSeq").asLong()).isEqualTo(1);
        assertThat(ack.get("id").asText()).isEqualTo(Long.toString(mq.events.get(0).id()));
        assertThat(svc.inFlight()).isZero();
    }

    @Test
    void returnsBusyWhenInFlightIsFullAndKeepsConnection() throws Exception {
        var svc = service(1, 10, true);
        svc.submit(ch, 5, 100, req(1));            // 占住唯一的名额
        svc.submit(ch, 5, 100, req(2));
        JsonNode err = reply();
        assertThat(err.get("code").asText()).isEqualTo("BUSY");
        assertThat(err.get("clientSeq").asLong()).isEqualTo(2);
        assertThat(ch.isActive()).isTrue();         // 关键：不断开连接
        mq.successes.get(0).run();                  // 第一条确认后名额释放
        reply();                                    // ack
        svc.submit(ch, 5, 100, req(3));
        assertThat(mq.events).hasSize(2);
    }

    @Test
    void mqFailureReturnsBusyAndReleasesPermit() throws Exception {
        var svc = service(1, 10, true);
        svc.submit(ch, 5, 100, req(1));
        mq.failures.get(0).accept(new RuntimeException("broker down"));
        assertThat(reply().get("code").asText()).isEqualTo("BUSY");
        assertThat(svc.inFlight()).isZero();
    }

    @Test
    void rateLimitsPerUser() throws Exception {
        var svc = service(10, 2, true);             // 每人桶容量 2，几乎不补充
        svc.submit(ch, 5, 100, req(1));
        svc.submit(ch, 5, 100, req(2));
        svc.submit(ch, 5, 100, req(3));
        assertThat(reply().get("code").asText()).isEqualTo("RATE_LIMITED");
        svc.submit(ch, 6, 100, req(4));             // 别的用户不受影响
        assertThat(mq.events).hasSize(3);
    }

    @Test
    void rejectsInvalidContentAndClosedVideo() throws Exception {
        var svc = service(10, 10, true);
        svc.submit(ch, 5, 100, new SendRequest(1L, " ", null, 1.0));
        assertThat(reply().get("code").asText()).isEqualTo("INVALID");
        svc.submit(ch, 5, 100, new SendRequest(2L, "hi", "red", 1.0));
        assertThat(reply().get("code").asText()).isEqualTo("INVALID");
        svc.submit(ch, 5, 100, new SendRequest(3L, "hi", null, -1.0));
        assertThat(reply().get("code").asText()).isEqualTo("INVALID");

        var closed = service(10, 10, false);
        closed.submit(ch, 5, 100, req(4));
        assertThat(reply().get("code").asText()).isEqualTo("VIDEO_CLOSED");
        assertThat(closed.inFlight()).isZero();
        assertThat(mq.events).isEmpty();
    }
}
