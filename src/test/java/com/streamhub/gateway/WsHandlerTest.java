package com.streamhub.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.streamhub.common.Metrics;
import com.streamhub.common.Protocol;
import com.streamhub.common.StreamhubProperties;
import com.streamhub.common.id.SnowflakeIdGenerator;
import com.streamhub.ingest.IngestService;
import com.streamhub.room.RoomRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class WsHandlerTest {
    private final Metrics metrics = new Metrics();
    private final RoomRegistry rooms = new RoomRegistry(new StreamhubProperties.Room(100, 50, 16384), metrics);
    private final JwtVerifier jwt = new JwtVerifier(JwtVerifierTest.SECRET);
    private final IngestService ingest = new IngestService(id -> CompletableFuture.completedFuture(true),
            (e, ok, fail) -> ok.run(), new SnowflakeIdGenerator(() -> 1, System::currentTimeMillis), metrics,
            new StreamhubProperties.Ingest(10, 5, 10, 30), System::nanoTime, System::currentTimeMillis);

    private EmbeddedChannel connect(String path) {
        var ch = new EmbeddedChannel(new WsHandler(rooms, ingest, jwt));
        ch.pipeline().fireUserEventTriggered(new HandshakeComplete(path, new DefaultHttpHeaders(), null));
        return ch;
    }

    private static JsonNode next(EmbeddedChannel ch) throws Exception {
        TextWebSocketFrame f = ch.readOutbound();
        try {
            return Protocol.JSON.readTree(f.text());
        } finally {
            f.release();
        }
    }

    @Test
    void guestCanJoinButMustAuthBeforeSending() throws Exception {
        var ch = connect("/ws/room/42");
        assertThat(rooms.connectionCount()).isEqualTo(1);

        ch.writeInbound(new TextWebSocketFrame("{\"op\":\"send\",\"clientSeq\":1,\"content\":\"hi\",\"playbackTime\":1}"));
        assertThat(next(ch).get("code").asText()).isEqualTo("UNAUTHORIZED");

        ch.writeInbound(new TextWebSocketFrame("{\"op\":\"auth\",\"token\":\"bad\"}"));
        assertThat(next(ch).get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(ch.isActive()).isTrue();          // 认证失败也不断开，游客继续看

        String token = jwt.issue(9, Duration.ofMinutes(5));
        ch.writeInbound(new TextWebSocketFrame("{\"op\":\"auth\",\"token\":\"" + token + "\"}"));
        assertThat(next(ch).get("op").asText()).isEqualTo("authOk");

        ch.writeInbound(new TextWebSocketFrame("{\"op\":\"send\",\"clientSeq\":2,\"content\":\"hi\",\"playbackTime\":1}"));
        JsonNode ack = next(ch);
        assertThat(ack.get("op").asText()).isEqualTo("ack");
        assertThat(ack.get("clientSeq").asLong()).isEqualTo(2);

        ch.close();
        assertThat(rooms.connectionCount()).isZero();
    }

    @Test
    void badRoomPathIsClosed() {
        var ch = connect("/ws/room/abc");
        Object frame = ch.readOutbound();
        assertThat(frame).isInstanceOf(CloseWebSocketFrame.class);
        ((CloseWebSocketFrame) frame).release();
        assertThat(ch.isActive()).isFalse();
        assertThat(rooms.connectionCount()).isZero();
    }

    @Test
    void malformedJsonGetsErrorNotDisconnect() throws Exception {
        var ch = connect("/ws/room/1");
        ch.writeInbound(new TextWebSocketFrame("{oops"));
        assertThat(next(ch).get("code").asText()).isEqualTo("INVALID");
        assertThat(ch.isActive()).isTrue();
    }
}
