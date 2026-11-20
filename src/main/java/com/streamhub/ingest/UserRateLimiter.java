package com.streamhub.ingest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 每个用户一个令牌桶，放在本地缓存里；5 分钟没发言的用户自动清掉，防止内存无限增长。
 * 局限：多节点时每个节点各算各的（同一用户连两台机器，额度翻倍）。弹幕场景可以接受；
 * 要严格全局限流需要放到 Redis 里，代价是每条弹幕多一次网络往返。
 */
final class UserRateLimiter {
    private final Cache<Long, TokenBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(5))
            .maximumSize(1_000_000)
            .build();
    private final double ratePerSec;
    private final int burst;
    private final LongSupplier nanoClock;

    UserRateLimiter(double ratePerSec, int burst, LongSupplier nanoClock) {
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.nanoClock = nanoClock;
    }

    boolean tryAcquire(long userId) {
        long now = nanoClock.getAsLong();
        return buckets.get(userId, id -> new TokenBucket(ratePerSec, burst, now)).tryAcquire(now);
    }
}
