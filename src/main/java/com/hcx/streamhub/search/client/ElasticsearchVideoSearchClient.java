package com.hcx.streamhub.search.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hcx.streamhub.search.config.SearchProperties;
import com.hcx.streamhub.search.dto.VideoSearchResult;
import com.hcx.streamhub.video.entity.Video;

@Component
public class ElasticsearchVideoSearchClient {

	private static final String JSON_CONTENT_TYPE = "application/json";

	private final SearchProperties properties;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	public ElasticsearchVideoSearchClient(SearchProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(2))
				.build();
	}

	public void ensureVideoIndex() {
		if (exists("/" + properties.getVideoIndexName())) {
			return;
		}
		String body = """
				{
				  "mappings": {
				    "properties": {
				      "videoId": { "type": "long" },
				      "title": { "type": "text", "analyzer": "standard" },
				      "description": { "type": "text", "analyzer": "standard" },
				      "status": { "type": "keyword" },
				      "createdAt": { "type": "date" }
				    }
				  }
				}
				""";
		send("PUT", "/" + properties.getVideoIndexName(), body);
	}

	public void indexVideo(Video video) {
		ensureVideoIndex();
		Map<String, Object> document = Map.of(
				"videoId", video.getId(),
				"title", video.getTitle(),
				"description", video.getDescription() == null ? "" : video.getDescription(),
				"status", video.getStatus(),
				"createdAt", video.getCreatedAt() == null ? "" : video.getCreatedAt().toString());
		try {
			String body = objectMapper.writeValueAsString(document);
			send("PUT", "/" + properties.getVideoIndexName() + "/_doc/" + video.getId(), body);
		}
		catch (IOException exception) {
			throw new IllegalStateException("failed to serialize video search document", exception);
		}
	}

	public VideoSearchResult search(String keyword, long from, long size) {
		ensureVideoIndex();
		String escapedKeyword;
		try {
			escapedKeyword = objectMapper.writeValueAsString(keyword);
		}
		catch (IOException exception) {
			throw new IllegalStateException("failed to serialize search keyword", exception);
		}
		String body = """
				{
				  "from": %d,
				  "size": %d,
				  "query": {
				    "bool": {
				      "filter": [
				        { "term": { "status": "PUBLISHED" } }
				      ],
				      "must": [
				        {
				          "multi_match": {
				            "query": %s,
				            "fields": ["title^3", "description"]
				          }
				        }
				      ]
				    }
				  }
				}
				""".formatted(from, size, escapedKeyword);
		String responseBody = send("POST", "/" + properties.getVideoIndexName() + "/_search", body);
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			long total = root.path("hits").path("total").path("value").asLong(0);
			List<Long> videoIds = new ArrayList<>();
			for (JsonNode hit : root.path("hits").path("hits")) {
				long videoId = hit.path("_source").path("videoId").asLong();
				if (videoId > 0) {
					videoIds.add(videoId);
				}
			}
			return new VideoSearchResult(videoIds, total);
		}
		catch (IOException exception) {
			throw new IllegalStateException("failed to parse elasticsearch response", exception);
		}
	}

	private boolean exists(String path) {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
				.timeout(Duration.ofSeconds(2))
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200;
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new IllegalStateException("failed to connect elasticsearch", exception);
		}
	}

	private String send(String method, String path, String body) {
		HttpRequest request = HttpRequest.newBuilder(uri(path))
				.timeout(Duration.ofSeconds(5))
				.header("Content-Type", JSON_CONTENT_TYPE)
				.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("elasticsearch request failed, status=%d, body=%s"
						.formatted(response.statusCode(), response.body()));
			}
			return response.body();
		}
		catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new IllegalStateException("failed to request elasticsearch", exception);
		}
	}

	private URI uri(String path) {
		String endpoint = properties.getElasticsearchEndpoint();
		String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
		String encodedPath = path.replace(" ", URLEncoder.encode(" ", StandardCharsets.UTF_8));
		return URI.create(normalizedEndpoint + encodedPath);
	}
}
