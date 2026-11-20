package com.streamhub.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void idsAreUniqueAndIncreasingEvenWithinOneMillisecond() {
        var clock = new AtomicLong(SnowflakeIdGenerator.EPOCH_MS + 1000);
        var gen = new SnowflakeIdGenerator(() -> 7, clock::get);
        var seen = new HashSet<Long>();
        long prev = -1;
        for (int i = 0; i < 3000; i++) {          // 时钟不动，全部在同一毫秒内
            long id = gen.nextId();
            assertThat(id).isGreaterThan(prev);
            assertThat(seen.add(id)).isTrue();
            assertThat(SnowflakeIdGenerator.workerOf(id)).isEqualTo(7);
            prev = id;
        }
    }

    @Test
    void differentWorkersNeverCollideAtTheSameMillisecond() {
        long now = SnowflakeIdGenerator.EPOCH_MS + 5000;
        var a = new SnowflakeIdGenerator(() -> 1, () -> now);
        var b = new SnowflakeIdGenerator(() -> 2, () -> now);
        assertThat(a.nextId()).isNotEqualTo(b.nextId());
    }

    @Test
    void refusesWithoutWorkerIdOrWhenClockJumpsBackFar() {
        var noLease = new SnowflakeIdGenerator(() -> -1, System::currentTimeMillis);
        assertThatThrownBy(noLease::nextId).isInstanceOf(IllegalStateException.class);

        var clock = new AtomicLong(SnowflakeIdGenerator.EPOCH_MS + 10_000);
        var gen = new SnowflakeIdGenerator(() -> 1, clock::get);
        gen.nextId();
        clock.addAndGet(-1000);                    // 回拨 1 秒
        assertThatThrownBy(gen::nextId).hasMessageContaining("backwards");
    }
}
