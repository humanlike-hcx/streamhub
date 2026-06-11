package com.hcx.streamhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

	@NotBlank
	private String endpoint;

	@NotBlank
	private String accessKey;

	@NotBlank
	private String secretKey;

	@NotBlank
	private String bucketName;
}
