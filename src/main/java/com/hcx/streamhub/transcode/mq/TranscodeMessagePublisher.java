package com.hcx.streamhub.transcode.mq;

import java.nio.charset.StandardCharsets;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcx.streamhub.transcode.dto.TranscodeTaskMessage;
import com.hcx.streamhub.transcode.config.TranscodeRocketMqProperties;

@Component
public class TranscodeMessagePublisher implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(TranscodeMessagePublisher.class);

	private final TranscodeRocketMqProperties rocketMqProperties;
	private final ObjectMapper objectMapper;
	private DefaultMQProducer producer;
	private volatile boolean running;

	public TranscodeMessagePublisher(TranscodeRocketMqProperties rocketMqProperties, ObjectMapper objectMapper) {
		this.rocketMqProperties = rocketMqProperties;
		this.objectMapper = objectMapper;
	}

	public void publish(TranscodeTaskMessage message) {
		send(message, 0);
	}

	public void publishDelayed(TranscodeTaskMessage message, int retryCount) {
		send(message, retryDelayLevel(retryCount));
	}

	private void send(TranscodeTaskMessage message, int delayLevel) {
		if (!running || producer == null) {
			throw new IllegalStateException("RocketMQ transcode producer is not running");
		}
		try {
			byte[] body = objectMapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
			Message rocketMessage = new Message(
					rocketMqProperties.getTopic(),
					rocketMqProperties.getTag(),
					message.taskId().toString(),
					body);
			if (delayLevel > 0) {
				rocketMessage.setDelayTimeLevel(delayLevel);
			}
			SendResult result = producer.send(rocketMessage);
			log.info("Published RocketMQ transcode message, taskId={}, videoId={}, msgId={}, status={}, delayLevel={}",
					message.taskId(), message.videoId(), result.getMsgId(), result.getSendStatus(), delayLevel);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Serialize transcode message failed", exception);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Publish RocketMQ transcode message failed", exception);
		}
	}

	private int retryDelayLevel(int retryCount) {
		if (retryCount <= 1) {
			return 3;
		}
		if (retryCount == 2) {
			return 4;
		}
		return 5;
	}

	@Override
	public void start() {
		if (running) {
			return;
		}
		try {
			DefaultMQProducer mqProducer = new DefaultMQProducer(rocketMqProperties.getProducerGroup());
			mqProducer.setNamesrvAddr(rocketMqProperties.getNameServer());
			mqProducer.start();
			this.producer = mqProducer;
			this.running = true;
			log.info("RocketMQ transcode producer started, nameServer={}, topic={}, group={}",
					rocketMqProperties.getNameServer(), rocketMqProperties.getTopic(),
					rocketMqProperties.getProducerGroup());
		}
		catch (Exception exception) {
			log.warn("RocketMQ transcode producer failed to start, nameServer={}",
					rocketMqProperties.getNameServer(), exception);
		}
	}

	@Override
	public void stop() {
		if (producer != null) {
			producer.shutdown();
		}
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
