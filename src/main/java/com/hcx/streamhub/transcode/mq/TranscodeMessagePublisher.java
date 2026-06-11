package com.hcx.streamhub.transcode.mq;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.hcx.streamhub.transcode.dto.TranscodeTaskMessage;

@Component
public class TranscodeMessagePublisher {

	private final RabbitTemplate rabbitTemplate;

	public TranscodeMessagePublisher(RabbitTemplate rabbitTemplate) {
		this.rabbitTemplate = rabbitTemplate;
	}

	public void publish(TranscodeTaskMessage message) {
		rabbitTemplate.convertAndSend(
				TranscodeRabbitNames.EXCHANGE,
				TranscodeRabbitNames.ROUTING_KEY,
				message);
	}
}
