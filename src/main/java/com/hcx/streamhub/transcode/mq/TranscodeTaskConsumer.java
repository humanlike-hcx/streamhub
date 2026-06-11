package com.hcx.streamhub.transcode.mq;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.transcode.config.TranscodeRocketMqProperties;
import com.hcx.streamhub.transcode.dto.TranscodeTaskMessage;
import com.hcx.streamhub.transcode.entity.TranscodeTask;
import com.hcx.streamhub.transcode.enums.TranscodeTaskStatus;
import com.hcx.streamhub.transcode.service.TranscodeTaskService;
import com.hcx.streamhub.transcode.service.VideoTranscodeService;

@Component
public class TranscodeTaskConsumer implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TranscodeTaskConsumer.class);

	private final TranscodeRocketMqProperties rocketMqProperties;
	private final ObjectMapper objectMapper;
	private final TranscodeTaskService transcodeTaskService;
	private final VideoTranscodeService videoTranscodeService;
	private DefaultMQPushConsumer consumer;
	private volatile boolean running;

	public TranscodeTaskConsumer(TranscodeRocketMqProperties rocketMqProperties, ObjectMapper objectMapper,
			TranscodeTaskService transcodeTaskService, VideoTranscodeService videoTranscodeService) {
		this.rocketMqProperties = rocketMqProperties;
		this.objectMapper = objectMapper;
		this.transcodeTaskService = transcodeTaskService;
		this.videoTranscodeService = videoTranscodeService;
	}

	public void consume(TranscodeTaskMessage message) {
		try {
			TranscodeTask task = transcodeTaskService.getById(message.taskId());
			if (!task.getVideoId().equals(message.videoId())) {
				log.warn("Ignore transcode message with mismatched video id, taskId={}, messageVideoId={}, taskVideoId={}",
						message.taskId(), message.videoId(), task.getVideoId());
				return;
			}
			if (!transcodeTaskService.tryStartTask(task.getId())) {
				TranscodeTask currentTask = transcodeTaskService.getById(task.getId());
				if (TranscodeTaskStatus.SUCCESS.name().equals(currentTask.getStatus())) {
					log.info("Skip duplicate transcode message because task already succeeded, taskId={}, videoId={}",
							currentTask.getId(), currentTask.getVideoId());
					return;
				}
				if (TranscodeTaskStatus.PROCESSING.name().equals(currentTask.getStatus())) {
					log.info("Skip duplicate transcode message because task is being processed, taskId={}, videoId={}",
							currentTask.getId(), currentTask.getVideoId());
					return;
				}
				if (TranscodeTaskStatus.FAILED.name().equals(currentTask.getStatus())) {
					log.info("Skip failed transcode task for now, taskId={}, videoId={}",
							currentTask.getId(), currentTask.getVideoId());
					return;
				}
				log.info("Skip transcode message because task status changed, taskId={}, status={}",
						currentTask.getId(), currentTask.getStatus());
				return;
			}

			log.info("Claimed transcode task, taskId={}, videoId={}", task.getId(), task.getVideoId());
			videoTranscodeService.transcode(message);
		}
		catch (BusinessException exception) {
			log.warn("Ignore transcode message because task does not exist, taskId={}, videoId={}",
					message.taskId(), message.videoId());
		}
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		try {
			DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(rocketMqProperties.getConsumerGroup());
			mqConsumer.setNamesrvAddr(rocketMqProperties.getNameServer());
			mqConsumer.setConsumeThreadMin(rocketMqProperties.getConsumeThreadMin());
			mqConsumer.setConsumeThreadMax(rocketMqProperties.getConsumeThreadMax());
			mqConsumer.setConsumeMessageBatchMaxSize(1);
			mqConsumer.subscribe(rocketMqProperties.getTopic(), rocketMqProperties.getTag());
			mqConsumer.registerMessageListener(new TranscodeMessageListener());
			mqConsumer.start();
			this.consumer = mqConsumer;
			this.running = true;
			log.info("RocketMQ transcode consumer started, nameServer={}, topic={}, tag={}, group={}",
					rocketMqProperties.getNameServer(), rocketMqProperties.getTopic(), rocketMqProperties.getTag(),
					rocketMqProperties.getConsumerGroup());
		}
		catch (Exception exception) {
			log.warn("RocketMQ transcode consumer failed to start, nameServer={}, topic={}",
					rocketMqProperties.getNameServer(), rocketMqProperties.getTopic(), exception);
		}
	}

	@Override
	public void stop() {
		if (consumer != null) {
			consumer.shutdown();
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private class TranscodeMessageListener implements MessageListenerConcurrently {

		@Override
		public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
			for (MessageExt messageExt : messages) {
				try {
					TranscodeTaskMessage message = objectMapper.readValue(messageExt.getBody(), TranscodeTaskMessage.class);
					log.info("Received RocketMQ transcode message, taskId={}, videoId={}, msgId={}, reconsumeTimes={}",
							message.taskId(), message.videoId(), messageExt.getMsgId(), messageExt.getReconsumeTimes());
					consume(message);
				}
				catch (Exception exception) {
					String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
					log.error("Consume RocketMQ transcode message failed, msgId={}, body={}",
							messageExt.getMsgId(), body, exception);
					return ConsumeConcurrentlyStatus.RECONSUME_LATER;
				}
			}
			return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
		}
	}
}
