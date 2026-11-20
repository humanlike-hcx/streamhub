package com.streamhub.common.id;

import java.time.Duration;

/**
 * "租约"存储的抽象：抢占、续期、释放。
 * 生产用 Redis 实现；单元测试用内存实现，不需要真的 Redis。
 */
public interface LeaseStore {
    /** 只有 key 不存在时才写入 owner（Redis 的 SET NX EX），成功返回 true。 */
    boolean tryClaim(String key, String owner, Duration ttl);

    /** 只有 key 当前属于 owner 才延长过期时间，防止把别人的租约续上。 */
    boolean renew(String key, String owner, Duration ttl);

    /** 只有 key 属于 owner 才删除。 */
    void release(String key, String owner);
}
