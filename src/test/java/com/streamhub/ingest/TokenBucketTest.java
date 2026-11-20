package com.streamhub.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenBucketTest {
    @Test
    void allowsBurstThenRefillsAtRate() {
        long t = 0;
        var bucket = new TokenBucket(5, 3, t);    // 每秒 5 个，桶容量 3
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isFalse();          // 桶空了
        t += 200_000_000L;                                   // 200ms 补 1 个
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isFalse();
        t += 10_000_000_000L;                                // 很久之后最多也只有 3 个
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isTrue();
        assertThat(bucket.tryAcquire(t)).isFalse();
    }
}
