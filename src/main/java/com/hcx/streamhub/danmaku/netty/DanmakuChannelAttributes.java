package com.hcx.streamhub.danmaku.netty;

import io.netty.util.AttributeKey;

public final class DanmakuChannelAttributes {

	public static final AttributeKey<Long> VIDEO_ID = AttributeKey.valueOf("danmakuVideoId");

	public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("danmakuUserId");

	private DanmakuChannelAttributes() {
	}
}
