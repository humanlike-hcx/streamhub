package com.hcx.streamhub.search.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.hcx.streamhub.common.PageRequest;
import com.hcx.streamhub.common.PageResponse;
import com.hcx.streamhub.search.client.ElasticsearchVideoSearchClient;
import com.hcx.streamhub.search.dto.VideoSearchResult;
import com.hcx.streamhub.video.dto.VideoDetailResponse;
import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.service.VideoService;

@Service
public class VideoSearchService {

	private static final Logger log = LoggerFactory.getLogger(VideoSearchService.class);

	private final ElasticsearchVideoSearchClient elasticsearchVideoSearchClient;
	private final VideoService videoService;

	public VideoSearchService(ElasticsearchVideoSearchClient elasticsearchVideoSearchClient, VideoService videoService) {
		this.elasticsearchVideoSearchClient = elasticsearchVideoSearchClient;
		this.videoService = videoService;
	}

	public PageResponse<VideoDetailResponse> search(String keyword, PageRequest request) {
		if (!StringUtils.hasText(keyword)) {
			return videoService.listPublished(request);
		}
		try {
			VideoSearchResult result = elasticsearchVideoSearchClient.search(keyword, request.offset(), request.getPageSize());
			return videoService.listPublishedByIdsInOrder(result.videoIds(), request, result.total());
		}
		catch (RuntimeException exception) {
			log.warn("Elasticsearch search failed, fallback to MySQL LIKE, keyword={}, error={}",
					keyword, exception.getMessage());
			return videoService.searchPublishedFromDatabase(keyword, request);
		}
	}

	public void indexPublishedVideo(Video video) {
		try {
			elasticsearchVideoSearchClient.indexVideo(video);
		}
		catch (RuntimeException exception) {
			log.warn("Failed to index published video, videoId={}, error={}", video.getId(), exception.getMessage());
		}
	}

	public void deleteVideo(Long videoId) {
		try {
			elasticsearchVideoSearchClient.deleteVideo(videoId);
		}
		catch (RuntimeException exception) {
			log.warn("Failed to delete video search index, videoId={}, error={}", videoId, exception.getMessage());
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	public void rebuildPublishedVideoIndexOnStartup() {
		try {
			var videos = videoService.listAllPublishedForIndex();
			for (Video video : videos) {
				elasticsearchVideoSearchClient.indexVideo(video);
			}
			log.info("Published video search index synchronized, count={}", videos.size());
		}
		catch (RuntimeException exception) {
			log.warn("Skip published video search index synchronization because Elasticsearch is unavailable, error={}",
					exception.getMessage());
		}
	}
}
