package com.hcx.streamhub.danmaku.room;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;

@Component
public class DanmakuRoomManager {

	private static final Logger log = LoggerFactory.getLogger(DanmakuRoomManager.class);

	private final ConcurrentMap<Long, ChannelGroup> rooms = new ConcurrentHashMap<>();

	public void join(Long videoId, Channel channel) {
		ChannelGroup room = rooms.computeIfAbsent(videoId, ignored -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
		if (room.add(channel)) {
			log.info("Danmaku channel joined room, videoId={}, channelId={}", videoId, channel.id().asShortText());
		}
	}

	public void leave(Long videoId, Channel channel) {
		if (videoId == null) {
			return;
		}
		ChannelGroup room = rooms.get(videoId);
		if (room == null) {
			return;
		}
		room.remove(channel);
		log.info("Danmaku channel left room, videoId={}, channelId={}", videoId, channel.id().asShortText());
		if (room.isEmpty()) {
			rooms.remove(videoId, room);
		}
	}

	public void broadcast(Long videoId, String message) {
		ChannelGroup room = rooms.get(videoId);
		if (room != null) {
			log.info("Broadcast danmaku message, videoId={}, receivers={}", videoId, room.size());
			room.writeAndFlush(new TextWebSocketFrame(message));
		}
	}
}
