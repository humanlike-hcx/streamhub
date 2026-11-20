package com.streamhub.common.id;

import com.streamhub.common.StreamhubProperties;
import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 给本实例分配雪花 workerId（决策 2B）。
 * - 配了 streamhub.worker-id（环境变量 STREAMHUB_WORKER_ID）就直接用，不碰 Redis。
 * - 否则启动时从 0 开始挨个 SET NX 抢 streamhub:worker:{n}，抢到就是我的；之后每 20 秒续期。
 *
 * 安全点：本地只把租约当作"TTL 减 10 秒"内有效。万一续期一直失败（比如连不上 Redis），
 * 本实例会在 Redis 里的 key 过期之前先停止发号（getAsInt 返回 -1，发送方回 BUSY），
 * 这样别的实例接手这个编号时，我们已经不再用它了，不会产生重复 ID。
 */
@Component
public class WorkerIdLease implements IntSupplier, SmartLifecycle {
    /**
     * 生命周期阶段：Netty(20) 和消费者(10) 先停，然后才还号；还号要用 Redis 连接，
     * 而 Redis 连接工厂在阶段 0 关闭，所以必须排在它前面（数字大的先停）。
     * 早先用 @PreDestroy 还号，实测那时 Redis 连接已经关了，号没还掉，只能等 60 秒过期。
     */
    public static final int PHASE = 5;
    private static final Logger log = LoggerFactory.getLogger(WorkerIdLease.class);
    static final String KEY_PREFIX = "streamhub:worker:";
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration SAFETY = Duration.ofSeconds(10);

    private final LeaseStore store;
    private final String owner;
    private final int fixedWorkerId;
    private final LongSupplier clock;

    private volatile int workerId = -1;
    private volatile long validUntilMs;
    private volatile boolean running;

    @Autowired
    public WorkerIdLease(LeaseStore store, StreamhubProperties props) {
        // owner 加一段随机后缀：同名节点重启后是"新进程"，不能续上旧进程留下的号（旧号等 TTL 过期）
        this(store, props.nodeId() + "#" + java.util.UUID.randomUUID().toString().substring(0, 8),
                props.workerId(), System::currentTimeMillis);
    }

    WorkerIdLease(LeaseStore store, String owner, int fixedWorkerId, LongSupplier clock) {
        this.store = store;
        this.owner = owner;
        this.fixedWorkerId = fixedWorkerId;
        this.clock = clock;
    }

    @Override
    public void start() {
        running = true;
        if (fixedWorkerId >= 0) {
            log.info("workerId fixed by config: {}", fixedWorkerId);
            return;
        }
        if (!claim()) {
            throw new IllegalStateException("no free workerId in Redis (0.." + SnowflakeIdGenerator.MAX_WORKER_ID + ")");
        }
    }

    /** 从小到大找一个空位。实例数远小于 1024，通常第一两次就能抢到。 */
    boolean claim() {
        for (int id = 0; id <= SnowflakeIdGenerator.MAX_WORKER_ID; id++) {
            long now = clock.getAsLong();
            if (store.tryClaim(KEY_PREFIX + id, owner, TTL)) {
                workerId = id;
                validUntilMs = now + TTL.minus(SAFETY).toMillis();
                log.info("claimed workerId {} as {}", id, owner);
                return true;
            }
        }
        return false;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    public void renew() {
        if (fixedWorkerId >= 0 || !running) {   // 已经还号了就别再续/抢
            return;
        }
        int id = workerId;
        long now = clock.getAsLong();
        try {
            if (id >= 0 && store.renew(KEY_PREFIX + id, owner, TTL)) {
                validUntilMs = now + TTL.minus(SAFETY).toMillis();
                return;
            }
            // 续期失败 = key 已经不是我的了（过期被别人抢 / 被删）。立刻停用并重新抢一个。
            log.warn("lost workerId {} lease, re-claiming", id);
            workerId = -1;
            claim();
        } catch (RuntimeException redisDown) {
            // Redis 暂时不可用：不改状态，靠 validUntilMs 自然过期来保证安全
            log.warn("workerId renew failed: {}", redisDown.toString());
        }
    }

    /** 当前可用的 workerId；租约不在有效期内返回 -1。 */
    @Override
    public int getAsInt() {
        if (fixedWorkerId >= 0) {
            return fixedWorkerId;
        }
        return clock.getAsLong() < validUntilMs ? workerId : -1;
    }

    @Override
    public void stop() {
        running = false;
        int id = workerId;
        workerId = -1;            // 先停止发号，再还号
        validUntilMs = 0;
        if (fixedWorkerId < 0 && id >= 0) {
            try {
                store.release(KEY_PREFIX + id, owner);   // 正常关闭就主动还号，不用等 60 秒过期
            } catch (RuntimeException ignored) {
                // 关闭阶段 Redis 不可用也没关系，key 会自己过期
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
