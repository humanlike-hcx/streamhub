package com.streamhub.common.health;

/**
 * 一个外部依赖的健康检查。
 * 为什么抽成接口：HealthController 只认这个接口，测试时可以塞假的实现，
 * 不需要真的 MySQL/Redis 就能测 /health 的逻辑。
 */
public interface DependencyCheck {

    /** 依赖名字，会出现在 /health 的返回里，比如 "mysql"。 */
    String name();

    /** 检查一次；不健康就直接抛异常（异常信息会返回给调用方，方便排查）。 */
    void check() throws Exception;
}
