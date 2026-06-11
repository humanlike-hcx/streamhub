package com.hcx.streamhub.danmaku.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.hcx.streamhub.danmaku.config.DanmakuNettyProperties;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

@Component
public class DanmakuNettyServer implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(DanmakuNettyServer.class);

	private final DanmakuNettyProperties properties;
	private final DanmakuChannelInitializer channelInitializer;

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel serverChannel;
	private volatile boolean running;

	public DanmakuNettyServer(DanmakuNettyProperties properties, DanmakuChannelInitializer channelInitializer) {
		this.properties = properties;
		this.channelInitializer = channelInitializer;
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		bossGroup = new NioEventLoopGroup(1);
		workerGroup = new NioEventLoopGroup();
		try {
			serverChannel = new ServerBootstrap()
					.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.childHandler(channelInitializer)
					.bind(properties.getNettyPort())
					.sync()
					.channel();
			running = true;
			log.info("Danmaku Netty WebSocket server started on port {}", properties.getNettyPort());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			stop();
			throw new IllegalStateException("failed to start danmaku netty server", exception);
		}
		catch (RuntimeException exception) {
			stop();
			throw exception;
		}
	}

	@Override
	public void stop() {
		if (serverChannel != null) {
			serverChannel.close();
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully();
		}
		if (bossGroup != null) {
			bossGroup.shutdownGracefully();
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
