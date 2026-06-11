package com.hcx.streamhub.danmaku.netty;

import org.springframework.stereotype.Component;

import com.hcx.streamhub.danmaku.config.DanmakuNettyProperties;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

@Component
public class DanmakuChannelInitializer extends ChannelInitializer<Channel> {

	private final DanmakuNettyProperties properties;
	private final DanmakuHandshakeHandler handshakeHandler;
	private final DanmakuFrameHandler frameHandler;

	public DanmakuChannelInitializer(DanmakuNettyProperties properties, DanmakuHandshakeHandler handshakeHandler,
			DanmakuFrameHandler frameHandler) {
		this.properties = properties;
		this.handshakeHandler = handshakeHandler;
		this.frameHandler = frameHandler;
	}

	@Override
	protected void initChannel(Channel channel) {
		channel.pipeline()
				.addLast(new HttpServerCodec())
				.addLast(new HttpObjectAggregator(65536))
				.addLast(new IdleStateHandler(properties.getHeartbeatTimeoutSeconds(), 0, 0))
				.addLast(handshakeHandler)
				.addLast(new WebSocketServerProtocolHandler(properties.getPath(), null, true))
				.addLast(frameHandler);
	}
}
