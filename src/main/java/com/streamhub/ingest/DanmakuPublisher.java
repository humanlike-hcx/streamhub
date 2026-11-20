package com.streamhub.ingest;

import com.streamhub.common.DanmakuEvent;
import java.util.function.Consumer;

/**
 * 把弹幕发到消息队列。异步：调用立刻返回，结果通过回调通知。
 * 抽成接口是为了单元测试时换成假的实现，不需要真的 RocketMQ。
 */
public interface DanmakuPublisher {
    void publish(DanmakuEvent event, Runnable onSuccess, Consumer<Throwable> onFailure);
}
