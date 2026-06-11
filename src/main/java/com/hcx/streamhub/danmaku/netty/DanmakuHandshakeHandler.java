package com.hcx.streamhub.danmaku.netty;

import java.util.List;

import org.springframework.stereotype.Component;

import com.hcx.streamhub.auth.security.AuthenticatedUser;
import com.hcx.streamhub.auth.security.JwtTokenProvider;
import com.hcx.streamhub.danmaku.config.DanmakuNettyProperties;
import com.hcx.streamhub.video.service.VideoService;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

@Component
@ChannelHandler.Sharable
public class DanmakuHandshakeHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private final DanmakuNettyProperties properties;
	private final JwtTokenProvider jwtTokenProvider;
	private final VideoService videoService;

	public DanmakuHandshakeHandler(DanmakuNettyProperties properties, JwtTokenProvider jwtTokenProvider,
			VideoService videoService) {
		this.properties = properties;
		this.jwtTokenProvider = jwtTokenProvider;
		this.videoService = videoService;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
		QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
		if (!properties.getPath().equals(decoder.path())) {
			reject(context, HttpResponseStatus.NOT_FOUND);
			return;
		}
		String videoIdText = first(decoder.parameters().get("videoId"));
		String token = first(decoder.parameters().get("token"));
		if (videoIdText == null || token == null) {
			reject(context, HttpResponseStatus.UNAUTHORIZED);
			return;
		}
		try {
			Long videoId = Long.valueOf(videoIdText);
			AuthenticatedUser user = jwtTokenProvider.parseToken(token);
			videoService.getPublishedVideo(videoId);
			context.channel().attr(DanmakuChannelAttributes.VIDEO_ID).set(videoId);
			context.channel().attr(DanmakuChannelAttributes.USER_ID).set(user.id());
			request.setUri(properties.getPath());
			context.fireChannelRead(request.retain());
		}
		catch (Exception exception) {
			reject(context, HttpResponseStatus.UNAUTHORIZED);
		}
	}

	private String first(List<String> values) {
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	private void reject(ChannelHandlerContext context, HttpResponseStatus status) {
		DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
		context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
	}
}
