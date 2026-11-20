package com.streamhub.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.streamhub.common.Protocol;
import com.streamhub.common.Protocol.ErrorCode;
import com.streamhub.ingest.IngestService;
import com.streamhub.ingest.SendRequest;
import com.streamhub.room.RoomRegistry;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个 WebSocket 连接的处理器（每个连接 new 一个，所以字段不用考虑并发：
 * 一个连接的所有事件都在同一个 EventLoop 线程里按顺序执行）。
 *
 * 消息类型：auth（登录，第一条）/ send（发弹幕）/ ping。
 * 不登录也能看弹幕（游客），只是不能发。所有错误都只回 error 帧，不断开连接。
 */
public class WsHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
    private static final Pattern PATH = Pattern.compile("^/ws/room/([1-9][0-9]{0,17})$");
    /** 连续这么多次 30 秒没收到任何数据（包括对 ping 的 pong），就认为连接已死。 */
    private static final int MAX_MISSED_PINGS = 2;

    private final RoomRegistry rooms;
    private final IngestService ingest;
    private final JwtVerifier jwt;

    private long videoId;      // 0 = 还没握手
    private Long userId;       // null = 游客
    private int missedPings;

    public WsHandler(RoomRegistry rooms, IngestService ingest, JwtVerifier jwt) {
        this.rooms = rooms;
        this.ingest = ingest;
        this.jwt = jwt;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete hs) {
            Matcher m = PATH.matcher(hs.requestUri());
            if (!m.matches()) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1008, "bad room path")).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            videoId = Long.parseLong(m.group(1));
            rooms.join(videoId, ctx.channel());
        } else if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            // 心跳：读空闲就发 ping（浏览器会自动回 pong），连续几次没回音就断开，回收资源
            if (++missedPings > MAX_MISSED_PINGS) {
                ctx.close();
            } else {
                ctx.writeAndFlush(new PingWebSocketFrame());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        missedPings = 0;   // 收到任何帧都说明连接活着
        if (frame instanceof PongWebSocketFrame) {
            return;
        }
        if (!(frame instanceof TextWebSocketFrame text)) {
            reply(ctx, Protocol.error(null, ErrorCode.INVALID, "只支持文本帧"));
            return;
        }
        JsonNode msg;
        try {
            msg = Protocol.JSON.readTree(text.text());
        } catch (Exception badJson) {
            reply(ctx, Protocol.error(null, ErrorCode.INVALID, "JSON 格式错误"));
            return;
        }
        switch (msg.path("op").asText()) {
            case "auth" -> auth(ctx, msg);
            case "send" -> send(ctx, msg);
            case "ping" -> reply(ctx, Protocol.pong());
            default -> reply(ctx, Protocol.error(null, ErrorCode.INVALID, "未知 op"));
        }
    }

    private void auth(ChannelHandlerContext ctx, JsonNode msg) {
        Long uid = jwt.verify(msg.path("token").asText(null));
        if (uid == null) {
            reply(ctx, Protocol.error(null, ErrorCode.UNAUTHORIZED, "token 无效或已过期"));
            return;
        }
        userId = uid;
        reply(ctx, Protocol.authOk(uid));
    }

    private void send(ChannelHandlerContext ctx, JsonNode msg) {
        Long seq = msg.hasNonNull("clientSeq") && msg.get("clientSeq").canConvertToLong() ? msg.get("clientSeq").asLong() : null;
        if (userId == null) {
            reply(ctx, Protocol.error(seq, ErrorCode.UNAUTHORIZED, "请先发送 auth"));
            return;
        }
        JsonNode t = msg.get("playbackTime");
        var req = new SendRequest(seq,
                msg.path("content").isTextual() ? msg.get("content").asText() : null,
                msg.path("color").isTextual() ? msg.get("color").asText() : null,
                t != null && t.isNumber() ? t.asDouble() : null);
        ingest.submit(ctx.channel(), userId, videoId, req);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (videoId != 0) {
            rooms.leave(videoId, ctx.channel());
            videoId = 0;
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // 走到这里的是协议层错误（坏帧、连接被对端重置），不是过载，关掉没问题
        log.debug("connection error: {}", cause.toString());
        ctx.close();
    }

    private static void reply(ChannelHandlerContext ctx, String json) {
        ctx.writeAndFlush(new TextWebSocketFrame(json));
    }
}
