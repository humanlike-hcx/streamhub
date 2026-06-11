package com.hcx.streamhub.danmaku.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "streamhub.danmaku")
public class DanmakuNettyProperties {

	private int nettyPort = 8090;

	private String path = "/ws/danmaku";

	private int heartbeatTimeoutSeconds = 60;
}
