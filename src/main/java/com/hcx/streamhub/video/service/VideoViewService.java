package com.hcx.streamhub.video.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.hcx.streamhub.common.PageRequest;
import com.hcx.streamhub.common.PageResponse;
import com.hcx.streamhub.video.dto.VideoDetailResponse;
import com.hcx.streamhub.video.entity.Video;

@Service
public class VideoViewService {

	private static final String HOT_VIDEO_KEY = "streamhub:video:hot";
	private static final String PLAY_DELTA_KEY = "streamhub:video:play:delta";
	private static final double VIEW_WEIGHT = 1D;
	private static final double LIKE_WEIGHT = 3D;
	private static final double COLLECT_WEIGHT = 4D;
	private static final double COMMENT_WEIGHT = 2D;

	private final StringRedisTemplate stringRedisTemplate;
	private final VideoService videoService;

	public VideoViewService(StringRedisTemplate stringRedisTemplate, VideoService videoService) {
		this.stringRedisTemplate = stringRedisTemplate;
		this.videoService = videoService;
	}

	public void recordPlay(Long videoId) {
		Video video = videoService.getPlayableVideo(videoId);
		refreshHotScore(video);
		stringRedisTemplate.opsForHash().increment(PLAY_DELTA_KEY, videoId.toString(), 1L);
		stringRedisTemplate.opsForZSet().incrementScore(HOT_VIDEO_KEY, videoId.toString(), VIEW_WEIGHT);
	}

	public PageResponse<VideoDetailResponse> listHot(PageRequest request) {
		long start = request.offset();
		long end = start + request.getPageSize() - 1;
		var hotItems = stringRedisTemplate.opsForZSet()
				.reverseRangeWithScores(HOT_VIDEO_KEY, start, end);
		if (hotItems == null || hotItems.isEmpty()) {
			return videoService.listHotFromDatabase(request);
		}
		List<Long> videoIds = hotItems.stream()
				.map(ZSetOperations.TypedTuple::getValue)
				.filter(Objects::nonNull)
				.map(Long::valueOf)
				.toList();
		Long total = stringRedisTemplate.opsForZSet().zCard(HOT_VIDEO_KEY);
		return videoService.listHotByIds(videoIds, request, total == null ? 0L : total, pendingPlayCounts(videoIds));
	}

	public void increaseLikeScore(Long videoId) {
		refreshHotScore(videoId);
	}

	public void decreaseLikeScore(Long videoId) {
		refreshHotScore(videoId);
	}

	public void increaseCollectScore(Long videoId) {
		refreshHotScore(videoId);
	}

	public void decreaseCollectScore(Long videoId) {
		refreshHotScore(videoId);
	}

	public void increaseCommentScore(Long videoId) {
		refreshHotScore(videoId);
	}

	@Scheduled(fixedDelay = 30000)
	public void flushPlayCountToDatabase() {
		Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(PLAY_DELTA_KEY);
		if (entries.isEmpty()) {
			return;
		}
		for (Map.Entry<Object, Object> entry : entries.entrySet()) {
			Long videoId = Long.valueOf(entry.getKey().toString());
			long delta = Long.parseLong(entry.getValue().toString());
			if (delta <= 0) {
				continue;
			}
			videoService.incrementPlayCount(videoId, delta);
			Long remaining = stringRedisTemplate.opsForHash().increment(PLAY_DELTA_KEY, videoId.toString(), -delta);
			if (remaining != null && remaining <= 0) {
				stringRedisTemplate.opsForHash().delete(PLAY_DELTA_KEY, videoId.toString());
			}
		}
	}

	private Map<Long, Long> pendingPlayCounts(List<Long> videoIds) {
		return videoIds.stream()
				.collect(java.util.stream.Collectors.toMap(
						videoId -> videoId,
						videoId -> {
							Object value = stringRedisTemplate.opsForHash().get(PLAY_DELTA_KEY, videoId.toString());
							return value == null ? 0L : Long.parseLong(value.toString());
						}));
	}

	private void refreshHotScore(Long videoId) {
		Video video = videoService.getPublishedVideo(videoId);
		refreshHotScore(video);
	}

	private void refreshHotScore(Video video) {
		String member = video.getId().toString();
		stringRedisTemplate.opsForZSet().add(HOT_VIDEO_KEY, member, calculateBaseHotScore(video));
	}

	private double calculateBaseHotScore(Video video) {
		long playCount = video.getPlayCount() == null ? 0L : video.getPlayCount();
		Object pendingPlayCountValue = stringRedisTemplate.opsForHash().get(PLAY_DELTA_KEY, video.getId().toString());
		long pendingPlayCount = pendingPlayCountValue == null ? 0L : Long.parseLong(pendingPlayCountValue.toString());
		long likeCount = video.getLikeCount() == null ? 0L : video.getLikeCount();
		long collectCount = video.getCollectCount() == null ? 0L : video.getCollectCount();
		long commentCount = video.getCommentCount() == null ? 0L : video.getCommentCount();
		return (playCount + pendingPlayCount) * VIEW_WEIGHT
				+ likeCount * LIKE_WEIGHT
				+ collectCount * COLLECT_WEIGHT
				+ commentCount * COMMENT_WEIGHT;
	}
}
