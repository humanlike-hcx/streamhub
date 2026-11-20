package com.streamhub.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 所有自定义配置集中在这里，值写在 application.yml（都能用环境变量覆盖）。
 * 用 record：只读、不可变，启动后没人能偷偷改配置。
 */
@ConfigurationProperties("streamhub")
public record StreamhubProperties(
        String nodeId,          // 实例名，空则启动时随机生成；用于 workerId 租约和 MQ 客户端名
        int workerId,           // >=0 表示手动指定雪花 workerId（环境变量覆盖）；-1 表示从 Redis 抢
        int wsPort,             // WebSocket 端口
        int ioThreads,          // Netty worker 线程数，0 = Netty 默认（CPU 核数 * 2）
        String jwtSecret,       // Base64 编码的 HS256 密钥，至少 32 字节
        boolean devEndpoints,   // 是否开放 /dev/* 测试接口（签发 token、建视频）
        Mq rocketmq,
        Room room,
        Ingest ingest,
        Persist persist) {

    public record Mq(String nameServer, String topic, int queues, long pushMaxAgeMs) {}

    /** windowMs=0 表示关闭合并（每条立刻推），用于对比实验。 */
    public record Room(int windowMs, int maxItems, int maxFrameBytes) {}

    public record Ingest(int maxInFlight, double userRatePerSec, int userBurst, int videoCacheSeconds) {}

    public record Persist(int batchSize, int threads) {}
}
