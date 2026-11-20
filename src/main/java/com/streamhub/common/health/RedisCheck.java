package com.streamhub.common.health;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/** 发 PING，期望收到 PONG。 */
@Component
public class RedisCheck implements DependencyCheck {
    private final RedisConnectionFactory factory;

    public RedisCheck(RedisConnectionFactory factory) {
        this.factory = factory;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public void check() {
        // try-with-resources：用完自动归还连接，避免连接泄漏
        try (RedisConnection connection = factory.getConnection()) {
            String reply = connection.ping();
            if (!"PONG".equalsIgnoreCase(reply)) {
                throw new IllegalStateException("unexpected PING reply: " + reply);
            }
        }
    }
}
