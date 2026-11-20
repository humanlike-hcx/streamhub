package com.streamhub.common.id;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 用 Redis 实现租约。
 * 续期和释放用 Lua 脚本：在 Redis 里"先比较 owner 再操作"一步完成（原子），
 * 否则 GET 和 EXPIRE 之间 key 可能已经过期并被别人抢走。
 */
@Component
public class RedisLeaseStore implements LeaseStore {
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLeaseStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryClaim(String key, String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl));
    }

    @Override
    public boolean renew(String key, String owner, Duration ttl) {
        Long r = redis.execute(RENEW, List.of(key), owner, String.valueOf(ttl.toMillis()));
        return r != null && r == 1L;
    }

    @Override
    public void release(String key, String owner) {
        redis.execute(RELEASE, List.of(key), owner);
    }
}
