package com.hcx.streamhub.transcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "streamhub.rocketmq")
public class TranscodeRocketMqProperties {

	private String nameServer = "localhost:9876";

	private String producerGroup = "streamhub-transcode-producer";

	private String consumerGroup = "streamhub-transcode-consumer";

	private String topic = "streamhub-transcode-task";

	private String tag = "TRANSCODE";

	private int consumeThreadMin = 1;

	private int consumeThreadMax = 2;
}
