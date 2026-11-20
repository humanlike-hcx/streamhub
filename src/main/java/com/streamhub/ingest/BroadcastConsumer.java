package com.streamhub.ingest;

import com.streamhub.common.DanmakuEvent;
import com.streamhub.common.Metrics;
import com.streamhub.common.Protocol;
import com.streamhub.common.StreamhubProperties;
import com.streamhub.room.RoomRegistry;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 跨节点推送（决策 1A）：广播消费模式，每个节点都会收到 topic 里的每一条弹幕，
 * 然后推给"本机"上这个房间的观众。这样连在不同机器上的人也能互相看到。
 *
 * 发送者所在节点也走这条路（不在网关里直接推本机），所有节点只有一条推送路径，逻辑简单。
 * 代价：多一次 MQ 往返（本机实测几毫秒）。
 *
 * 推送是"尽力而为"：太旧的消息（比如节点重启后读到的积压）直接丢掉，
 * 历史弹幕由查询接口提供；可靠性由另一个消费组（落库）保证。
 */
@Component
public class BroadcastConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BroadcastConsumer.class);
    private final DefaultMQPushConsumer consumer;
    private final RoomRegistry rooms;
    private final Metrics metrics;
    private final long maxAgeMs;
    private volatile boolean running;

    public BroadcastConsumer(StreamhubProperties props, RoomRegistry rooms, Metrics metrics) throws Exception {
        this.rooms = rooms;
        this.metrics = metrics;
        this.maxAgeMs = props.rocketmq().pushMaxAgeMs();
        consumer = new DefaultMQPushConsumer("streamhub-push");
        consumer.setNamesrvAddr(props.rocketmq().nameServer());
        consumer.setInstanceName(props.nodeId());   // 广播模式的消费进度存在本地文件，按实例名区分
        consumer.setMessageModel(MessageModel.BROADCASTING);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        consumer.setConsumeThreadMin(2);
        consumer.setConsumeThreadMax(2);   // 这里只是把任务丢给 EventLoop，很快，不需要多线程
        consumer.setPullBatchSize(64);
        consumer.setConsumeMessageBatchMaxSize(64);
        consumer.subscribe(props.rocketmq().topic(), "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            long now = System.currentTimeMillis();
            for (MessageExt m : messages) {
                metrics.broadcastReceived.increment();
                try {
                    DanmakuEvent e = Protocol.JSON.readValue(m.getBody(), DanmakuEvent.class);
                    if (now - e.sentAt() > maxAgeMs) {
                        metrics.staleDropped.increment();
                        continue;
                    }
                    rooms.publish(e.videoId(), Protocol.item(e));
                } catch (Exception bad) {
                    log.warn("skip malformed message {}: {}", m.getMsgId(), bad.toString());
                }
            }
            // 推送失败不重试：重试意味着观众收到重复弹幕或很晚才收到，没意义
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
    }

    @Override
    public void start() {
        try {
            consumer.start();
        } catch (Exception e) {
            throw new IllegalStateException("broadcast consumer start failed", e);
        }
        running = true;
    }

    @Override
    public void stop() {
        consumer.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return RocketMqPublisher.PHASE + 10;   // 生产者之后启动、之前关闭
    }
}
