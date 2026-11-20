package com.streamhub.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerIdLeaseTest {
    /** 内存版租约存储，带过期时间，模拟 Redis 的 SET NX EX。 */
    static final class MemoryStore implements LeaseStore {
        final Map<String, String> owners = new HashMap<>();
        final Map<String, Long> expires = new HashMap<>();
        final AtomicLong clock;

        MemoryStore(AtomicLong clock) {
            this.clock = clock;
        }

        private void expire(String key) {
            Long at = expires.get(key);
            if (at != null && at <= clock.get()) {
                owners.remove(key);
                expires.remove(key);
            }
        }

        @Override
        public boolean tryClaim(String key, String owner, Duration ttl) {
            expire(key);
            if (owners.containsKey(key)) {
                return false;
            }
            owners.put(key, owner);
            expires.put(key, clock.get() + ttl.toMillis());
            return true;
        }

        @Override
        public boolean renew(String key, String owner, Duration ttl) {
            expire(key);
            if (!owner.equals(owners.get(key))) {
                return false;
            }
            expires.put(key, clock.get() + ttl.toMillis());
            return true;
        }

        @Override
        public void release(String key, String owner) {
            if (owner.equals(owners.get(key))) {
                owners.remove(key);
            }
        }
    }

    @Test
    void twoInstancesGetDifferentIds() {
        var clock = new AtomicLong(1_000_000);
        var store = new MemoryStore(clock);
        var a = new WorkerIdLease(store, "node-a", -1, clock::get);
        var b = new WorkerIdLease(store, "node-b", -1, clock::get);
        a.start();
        b.start();
        assertThat(a.getAsInt()).isEqualTo(0);
        assertThat(b.getAsInt()).isEqualTo(1);
    }

    @Test
    void envOverrideSkipsRedis() {
        var lease = new WorkerIdLease(null, "node-a", 42, System::currentTimeMillis);
        lease.start();
        assertThat(lease.getAsInt()).isEqualTo(42);
    }

    @Test
    void stopsIssuingBeforeRedisKeyCanExpireWhenRenewalIsImpossible() {
        var clock = new AtomicLong(1_000_000);
        var store = new MemoryStore(clock);
        var a = new WorkerIdLease(store, "node-a", -1, clock::get);
        a.start();
        clock.addAndGet(WorkerIdLease.TTL.minus(WorkerIdLease.SAFETY).toMillis() + 1);   // 一直没续期
        assertThat(a.getAsInt()).isEqualTo(-1);        // 本地先失效（Redis 里还有 10 秒才过期）
    }

    @Test
    void reclaimsAfterLosingLease() {
        var clock = new AtomicLong(1_000_000);
        var store = new MemoryStore(clock);
        var a = new WorkerIdLease(store, "node-a", -1, clock::get);
        a.start();
        clock.addAndGet(WorkerIdLease.TTL.toMillis() + 1);      // Redis 里过期了
        var b = new WorkerIdLease(store, "node-b", -1, clock::get);
        b.start();                                              // b 抢到了 0
        a.renew();                                              // a 续期失败 → 重新抢 → 1
        assertThat(b.getAsInt()).isEqualTo(0);
        assertThat(a.getAsInt()).isEqualTo(1);
    }

    @Test
    void renewKeepsLeaseAlive() {
        var clock = new AtomicLong(1_000_000);
        var store = new MemoryStore(clock);
        var a = new WorkerIdLease(store, "node-a", -1, clock::get);
        a.start();
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(20_000);
            a.renew();
        }
        assertThat(a.getAsInt()).isEqualTo(0);
    }

    @Test
    void stopReleasesAndDoesNotReclaim() {
        var clock = new AtomicLong(1_000_000);
        var store = new MemoryStore(clock);
        var a = new WorkerIdLease(store, "node-a", -1, clock::get);
        a.start();
        a.stop();
        assertThat(a.getAsInt()).isEqualTo(-1);              // 停了就不再发号
        assertThat(store.owners).doesNotContainKey(WorkerIdLease.KEY_PREFIX + 0);  // 号已归还
        a.renew();                                           // 关闭后的定时续期不能把号又抢回来
        assertThat(store.owners).isEmpty();
        var b = new WorkerIdLease(store, "node-b", -1, clock::get);
        b.start();
        assertThat(b.getAsInt()).isEqualTo(0);               // 别的实例马上能用这个号
    }
}
