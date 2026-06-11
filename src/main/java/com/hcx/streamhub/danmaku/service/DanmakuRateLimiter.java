package com.hcx.streamhub.danmaku.service;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.hcx.streamhub.danmaku.config.DanmakuNettyProperties;

@Service
public class DanmakuRateLimiter {

	private static final String KEY_PREFIX = "streamhub:danmaku:rate:";

	private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
			local current = redis.call('incr', KEYS[1])
			if current == 1 then
			  redis.call('expire', KEYS[1], ARGV[1])
			end
			return current
			""", Long.class);

	private final StringRedisTemplate stringRedisTemplate;
	private final DanmakuNettyProperties properties;

	public DanmakuRateLimiter(StringRedisTemplate stringRedisTemplate, DanmakuNettyProperties properties) {
		this.stringRedisTemplate = stringRedisTemplate;
		this.properties = properties;
	}

	public boolean allow(Long videoId, Long userId) {
		String key = KEY_PREFIX + videoId + ":" + userId;
		Long count = stringRedisTemplate.execute(
				FIXED_WINDOW_SCRIPT,
				List.of(key),
				String.valueOf(properties.getRateLimitWindowSeconds()));
		return count != null && count <= properties.getRateLimitMaxMessages();
	}
}
