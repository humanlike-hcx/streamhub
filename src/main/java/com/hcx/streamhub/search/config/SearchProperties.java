package com.hcx.streamhub.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "streamhub.search")
public class SearchProperties {

	private String elasticsearchEndpoint = "http://localhost:9200";

	private String videoIndexName = "streamhub_videos";
}
