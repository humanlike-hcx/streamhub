package com.hcx.streamhub.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

	private final RedisProperties redisProperties;

	public RedissonConfig(RedisProperties redisProperties) {
		this.redisProperties = redisProperties;
	}

	@Bean(destroyMethod = "shutdown")
	public RedissonClient redissonClient() {
		Config config = new Config();
		String address = "redis://%s:%d".formatted(redisProperties.getHost(), redisProperties.getPort());
		var serverConfig = config.useSingleServer()
				.setAddress(address)
				.setDatabase(redisProperties.getDatabase());
		if (StringUtils.hasText(redisProperties.getPassword())) {
			serverConfig.setPassword(redisProperties.getPassword());
		}
		return Redisson.create(config);
	}
}
