package com.streamhub.ingest;

import com.streamhub.common.DanmakuEvent;
import com.streamhub.common.Protocol;
import com.streamhub.common.StreamhubProperties;
import java.util.function.Consumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 生产者，异步发送（决策 4A：MQ 确认后才给用户回 ack）。
 * 异步 vs 同步：同步发送会让调用线程干等 MQ 回复（EchoFrame 就是这样，4 个线程被占满）；
 * 异步发送只是把请求交出去，回复到了再走回调，调用线程马上去处理下一条。
 *
 * SmartLifecycle：控制启动/关闭顺序。生产者最先启动、最后关闭，
 * 保证网关在线的时候生产者一定可用。
 */
@Component
public class RocketMqPublisher implements DanmakuPublisher, SmartLifecycle {
    public static final int PHASE = 0;
    private final DefaultMQProducer producer;
    private final String topic;
    private volatile boolean running;

    public RocketMqPublisher(StreamhubProperties props) {
        this.topic = props.rocketmq().topic();
        this.producer = new DefaultMQProducer("streamhub-producer");
        producer.setNamesrvAddr(props.rocketmq().nameServer());
        producer.setInstanceName(props.nodeId());
        producer.setSendMsgTimeout(3000);
        producer.setRetryTimesWhenSendAsyncFailed(2);
        // 自动建 topic 时的队列数；队列越多，落库消费者能并行的线程越多（推荐用 mqadmin 显式建 topic）
        producer.setDefaultTopicQueueNums(props.rocketmq().queues());
    }

    @Override
    public void publish(DanmakuEvent event, Runnable onSuccess, Consumer<Throwable> onFailure) {
        try {
            byte[] body = Protocol.JSON.writeValueAsBytes(event);
            // key = 弹幕 id：出问题时可以用 mqadmin 按 key 查到这条消息
            Message msg = new Message(topic, "danmaku", Long.toString(event.id()), body);
            producer.send(msg, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    if (result.getSendStatus() == SendStatus.SEND_OK) {
                        onSuccess.run();
                    } else {
                        onFailure.accept(new IllegalStateException(result.getSendStatus().name()));
                    }
                }

                @Override
                public void onException(Throwable e) {
                    onFailure.accept(e);
                }
            });
        } catch (Exception e) {   // 序列化失败、生产者没启动、本地队列满等同步异常
            onFailure.accept(e);
        }
    }

    @Override
    public void start() {
        try {
            producer.start();
        } catch (Exception e) {
            throw new IllegalStateException("RocketMQ producer start failed", e);
        }
        running = true;
    }

    @Override
    public void stop() {
        producer.shutdown();
        running = false;
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
