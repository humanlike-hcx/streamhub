package com.streamhub.common.id;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 雪花 ID：64 位 = 1 位符号(0) + 41 位毫秒时间戳 + 10 位 workerId + 12 位序号。
 * - 时间戳在高位：ID 大致按时间递增，MySQL 主键插入更顺序。
 * - workerId 区分实例：不同机器同一毫秒也不会撞（这正是 EchoFrame 写死 1 的问题）。
 * - 序号：同一毫秒内最多 4096 个，用完就等下一毫秒。
 */
public final class SnowflakeIdGenerator {
    /** 自定义纪元 2026-01-01T00:00:00Z：41 位时间戳从这里起算，约可用 69 年。 */
    static final long EPOCH_MS = 1767225600000L;
    static final int WORKER_BITS = 10;
    static final int SEQUENCE_BITS = 12;
    static final int MAX_WORKER_ID = (1 << WORKER_BITS) - 1;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    /** 时钟小幅回拨（NTP 校时常见）就原地等；回拨太多直接报错，宁可拒绝也不发重复 ID。 */
    private static final long MAX_BACKWARD_MS = 5;

    private final IntSupplier workerId;   // 由 WorkerIdLease 提供，租约失效时返回 -1
    private final LongSupplier clock;
    private long lastMs = -1;
    private long sequence;

    public SnowflakeIdGenerator(IntSupplier workerId, LongSupplier clock) {
        this.workerId = workerId;
        this.clock = clock;
    }

    /** synchronized：同一实例内多个线程同时取号，必须串行才能保证序号不重复。临界区很短。 */
    public synchronized long nextId() {
        int worker = workerId.getAsInt();
        if (worker < 0 || worker > MAX_WORKER_ID) {
            throw new IllegalStateException("workerId lease not held");
        }
        long now = clock.getAsLong();
        if (now < lastMs) {
            long backward = lastMs - now;
            if (backward > MAX_BACKWARD_MS) {
                throw new IllegalStateException("clock moved backwards by " + backward + "ms");
            }
            now = waitUntil(lastMs);
        }
        if (now == lastMs) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {            // 这一毫秒的 4096 个号用完了
                now = waitUntil(lastMs + 1);
            }
        } else {
            sequence = 0;
        }
        lastMs = now;
        return ((now - EPOCH_MS) << (WORKER_BITS + SEQUENCE_BITS))
                | ((long) worker << SEQUENCE_BITS)
                | sequence;
    }

    /** 从 ID 里取出 workerId，测试和排查问题用。 */
    public static int workerOf(long id) {
        return (int) ((id >>> SEQUENCE_BITS) & MAX_WORKER_ID);
    }

    private long waitUntil(long targetMs) {
        long now = clock.getAsLong();
        while (now < targetMs) {
            Thread.onSpinWait();
            now = clock.getAsLong();
        }
        return now;
    }
}
