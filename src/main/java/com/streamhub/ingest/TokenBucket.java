package com.streamhub.ingest;

/**
 * 令牌桶：桶里最多 capacity 个令牌，每秒补 ratePerSec 个；发一条弹幕拿走一个，没有就拒绝。
 * 和"每秒最多 N 条"的固定窗口比：允许短时间突发（桶是满的时候可以连发几条），
 * 长期平均又被限制在 ratePerSec，不会出现窗口边界瞬间翻倍的问题。
 */
final class TokenBucket {
    private final double capacity;
    private final double ratePerNano;
    private double tokens;
    private long lastNanos;

    TokenBucket(double ratePerSec, int capacity, long nowNanos) {
        this.capacity = capacity;
        this.ratePerNano = ratePerSec / 1e9;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** synchronized：同一用户可能多个连接（多个线程）同时发。 */
    synchronized boolean tryAcquire(long nowNanos) {
        if (nowNanos > lastNanos) {
            tokens = Math.min(capacity, tokens + (nowNanos - lastNanos) * ratePerNano);
            lastNanos = nowNanos;
        }
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
