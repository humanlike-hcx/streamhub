package com.hcx.streamhub.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "streamhub.jwt")
public class JwtProperties {

	private String secret;

	private long expiration;

	private String issuer;
}
