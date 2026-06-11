package com.hcx.streamhub.danmaku.netty;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcx.streamhub.danmaku.room.DanmakuRoomManager;
import com.hcx.streamhub.danmaku.service.DanmakuRateLimiter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

@Component
@ChannelHandler.Sharable
public class DanmakuFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

	private final DanmakuRoomManager roomManager;
	private final DanmakuRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;

	public DanmakuFrameHandler(DanmakuRoomManager roomManager, DanmakuRateLimiter rateLimiter, ObjectMapper objectMapper) {
		this.roomManager = roomManager;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext context, WebSocketFrame frame) throws Exception {
		if (frame instanceof PingWebSocketFrame pingFrame) {
			context.writeAndFlush(new PongWebSocketFrame(pingFrame.content().retain()));
			return;
		}
		if (!(frame instanceof TextWebSocketFrame textFrame)) {
			return;
		}
		String text = textFrame.text();
		if (isPingText(text)) {
			context.writeAndFlush(new TextWebSocketFrame("{\"type\":\"PONG\"}"));
			return;
		}
		Long videoId = context.channel().attr(DanmakuChannelAttributes.VIDEO_ID).get();
		Long userId = context.channel().attr(DanmakuChannelAttributes.USER_ID).get();
		if (videoId == null || userId == null) {
			context.close();
			return;
		}
		String content = extractContent(text);
		if (content.isBlank()) {
			return;
		}
		if (!rateLimiter.allow(videoId, userId)) {
			context.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(Map.of(
					"type", "ERROR",
					"code", "DANMAKU_RATE_LIMITED",
					"message", "发送太频繁，请稍后再试"))));
			return;
		}
		String payload = objectMapper.writeValueAsString(Map.of(
				"type", "DANMAKU",
				"videoId", videoId,
				"userId", userId,
				"content", content,
				"createdAt", LocalDateTime.now().toString()));
		roomManager.broadcast(videoId, payload);
	}

	@Override
	public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
		if (event == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE
				|| event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
			joinRoom(context);
			return;
		}
		if (event instanceof IdleStateEvent idleEvent && idleEvent.state() == IdleState.READER_IDLE) {
			context.close();
			return;
		}
		super.userEventTriggered(context, event);
	}

	private void joinRoom(ChannelHandlerContext context) {
		Long videoId = context.channel().attr(DanmakuChannelAttributes.VIDEO_ID).get();
		if (videoId != null) {
			roomManager.join(videoId, context.channel());
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) throws Exception {
		roomManager.leave(context.channel().attr(DanmakuChannelAttributes.VIDEO_ID).get(), context.channel());
		super.channelInactive(context);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
		context.close();
	}

	private boolean isPingText(String text) {
		return "ping".equalsIgnoreCase(text) || "{\"type\":\"PING\"}".equalsIgnoreCase(text.replace(" ", ""));
	}

	private String extractContent(String text) {
		try {
			var node = objectMapper.readTree(text);
			if (node.hasNonNull("content")) {
				return node.get("content").asText().trim();
			}
		}
		catch (Exception ignored) {
		}
		return text.trim();
	}
}
