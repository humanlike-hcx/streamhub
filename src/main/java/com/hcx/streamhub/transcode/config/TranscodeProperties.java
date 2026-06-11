package com.hcx.streamhub.transcode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "streamhub.transcode")
public class TranscodeProperties {

	private String ffmpegPath = "ffmpeg";

	private String ffprobePath = "ffprobe";

	private String workDir = "target/transcode";
}
