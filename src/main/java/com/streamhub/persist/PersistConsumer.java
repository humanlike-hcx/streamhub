package com.streamhub.persist;

import com.streamhub.common.DanmakuEvent;
import com.streamhub.common.Metrics;
import com.streamhub.common.Protocol;
import com.streamhub.common.StreamhubProperties;
import com.streamhub.ingest.RocketMqPublisher;
import java.util.ArrayList;
import java.util.List;
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
 * 落库消费者：集群消费模式，同一个消费组里所有节点"分摊"消息，每条只被一个节点处理。
 * （同一个 topic，推送组是广播：每个节点都收到；落库组是集群：只处理一次。）
 *
 * 可靠性：RocketMQ 是"至少投递一次"——处理成功才提交进度；进程被 kill -9 时，
 * 最近一段已处理但还没提交进度的消息会被再投一次，靠 DanmakuWriter 的 INSERT IGNORE 去重。
 *
 * 吞吐：一次最多拿 batchSize 条一起写库（EchoFrame 实测的瓶颈是每 200ms 拉一次、
 * 每次最多 32 条，只有约 630 行/秒）。这里拉取间隔为 0，批次更大。
 */
@Component
public class PersistConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PersistConsumer.class);
    private final DefaultMQPushConsumer consumer;
    private final DanmakuWriter writer;
    private final Metrics metrics;
    private volatile boolean running;

    public PersistConsumer(StreamhubProperties props, DanmakuWriter writer, Metrics metrics) throws Exception {
        this.writer = writer;
        this.metrics = metrics;
        var cfg = props.persist();
        consumer = new DefaultMQPushConsumer("streamhub-persist");
        consumer.setNamesrvAddr(props.rocketmq().nameServer());
        consumer.setInstanceName(props.nodeId());
        consumer.setMessageModel(MessageModel.CLUSTERING);
        // 新消费组第一次启动时从最早的消息开始，保证 topic 里的弹幕一条不漏
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(cfg.threads());
        consumer.setConsumeThreadMax(cfg.threads());
        consumer.setPullBatchSize(cfg.batchSize());
        consumer.setConsumeMessageBatchMaxSize(cfg.batchSize());
        consumer.setPullInterval(0);
        consumer.subscribe(props.rocketmq().topic(), "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            List<DanmakuEvent> events = new ArrayList<>(messages.size());
            for (MessageExt m : messages) {
                try {
                    events.add(Protocol.JSON.readValue(m.getBody(), DanmakuEvent.class));
                } catch (Exception bad) {
                    // 解析不了的消息重试也没用（毒消息），记日志跳过，不能卡住整个队列
                    log.warn("skip malformed message {}: {}", m.getMsgId(), bad.toString());
                }
            }
            long start = System.nanoTime();
            try {
                int inserted = writer.write(events);
                metrics.persistBatches.increment();
                metrics.persistMessages.add(events.size());
                metrics.persistInserted.add(inserted);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            } catch (Exception dbError) {
                // 整批回滚，稍后整批重投；因为写入是幂等的，重投不会产生重复数据
                metrics.persistFailures.increment();
                log.warn("persist batch of {} failed, will retry: {}", events.size(), dbError.toString());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } finally {
                metrics.persistNanos.add(System.nanoTime() - start);
            }
        });
    }

    @Override
    public void start() {
        try {
            consumer.start();
        } catch (Exception e) {
            throw new IllegalStateException("persist consumer start failed", e);
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
        return RocketMqPublisher.PHASE + 10;
    }
}
