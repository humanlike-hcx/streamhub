package com.hcx.streamhub.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.common.PageRequest;
import com.hcx.streamhub.common.PageResponse;
import com.hcx.streamhub.video.dto.VideoDetailResponse;
import com.hcx.streamhub.video.dto.VideoResponse;
import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.enums.VideoStatus;
import com.hcx.streamhub.video.mapper.VideoMapper;

@Service
public class VideoService {

	private final VideoMapper videoMapper;

	public VideoService(VideoMapper videoMapper) {
		this.videoMapper = videoMapper;
	}

	@Transactional
	public Video createWaitingVideo(Long userId, String title, String description, String originalObjectKey,
			Long fileSize) {
		Video video = new Video();
		video.setUserId(userId);
		video.setTitle(title);
		video.setDescription(description);
		video.setOriginalObjectKey(originalObjectKey);
		video.setStatus(VideoStatus.WAITING.name());
		video.setFileSize(fileSize);
		video.setLikeCount(0L);
		video.setCollectCount(0L);
		video.setCommentCount(0L);
		video.setPlayCount(0L);
		videoMapper.insert(video);
		return video;
	}

	public Video getById(Long videoId) {
		Video video = videoMapper.selectById(videoId);
		if (video == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "video not found");
		}
		return video;
	}

	public Video getPlayableVideo(Long videoId) {
		Video video = getById(videoId);
		if (!VideoStatus.PUBLISHED.name().equals(video.getStatus()) || video.getHlsMasterObjectKey() == null) {
			throw new BusinessException(ErrorCode.VIDEO_NOT_PLAYABLE);
		}
		return video;
	}

	public Video getPublishedVideo(Long videoId) {
		Video video = getById(videoId);
		if (!VideoStatus.PUBLISHED.name().equals(video.getStatus())) {
			throw new BusinessException(ErrorCode.VIDEO_NOT_PLAYABLE, "video is not published");
		}
		return video;
	}

	public PageResponse<VideoDetailResponse> listPublished(PageRequest request) {
		Page<Video> page = new Page<>(request.getPageNo(), request.getPageSize());
		LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>()
				.eq(Video::getStatus, VideoStatus.PUBLISHED.name())
				.orderByDesc(Video::getCreatedAt);
		return PageResponse.from(videoMapper.selectPage(page, wrapper), VideoDetailResponse::from);
	}

	public PageResponse<VideoDetailResponse> listByUser(Long userId, PageRequest request) {
		Page<Video> page = new Page<>(request.getPageNo(), request.getPageSize());
		LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>()
				.eq(Video::getUserId, userId)
				.orderByDesc(Video::getCreatedAt);
		return PageResponse.from(videoMapper.selectPage(page, wrapper), VideoDetailResponse::from);
	}

	public PageResponse<VideoDetailResponse> listHotByIds(List<Long> videoIds, PageRequest request, long total,
			Map<Long, Long> pendingPlayCounts) {
		if (videoIds.isEmpty()) {
			return listHotFromDatabase(request);
		}
		Map<Long, Video> videoMap = videoMapper.selectBatchIds(videoIds).stream()
				.filter(video -> VideoStatus.PUBLISHED.name().equals(video.getStatus()))
				.collect(Collectors.toMap(Video::getId, Function.identity()));
		List<VideoDetailResponse> result = new ArrayList<>();
		for (Long videoId : videoIds) {
			Video video = videoMap.get(videoId);
			if (video != null) {
				long dbPlayCount = video.getPlayCount() == null ? 0L : video.getPlayCount();
				long pendingPlayCount = pendingPlayCounts.getOrDefault(videoId, 0L);
				result.add(VideoDetailResponse.from(video, dbPlayCount + pendingPlayCount));
			}
		}
		if (result.isEmpty()) {
			return listHotFromDatabase(request);
		}
		long pages = (total + request.getPageSize() - 1) / request.getPageSize();
		return new PageResponse<>(result, total, request.getPageNo(), request.getPageSize(), pages);
	}

	public PageResponse<VideoDetailResponse> listHotFromDatabase(PageRequest request) {
		Page<Video> page = new Page<>(request.getPageNo(), request.getPageSize());
		LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>()
				.eq(Video::getStatus, VideoStatus.PUBLISHED.name())
				.orderByDesc(Video::getPlayCount)
				.orderByDesc(Video::getLikeCount)
				.orderByDesc(Video::getCollectCount)
				.orderByDesc(Video::getCommentCount)
				.orderByDesc(Video::getCreatedAt);
		return PageResponse.from(videoMapper.selectPage(page, wrapper), VideoDetailResponse::from);
	}

	public PageResponse<VideoDetailResponse> listPublishedByIdsInOrder(List<Long> videoIds, PageRequest request,
			long total) {
		if (videoIds.isEmpty()) {
			return new PageResponse<>(List.of(), total, request.getPageNo(), request.getPageSize(),
					(total + request.getPageSize() - 1) / request.getPageSize());
		}
		Map<Long, Video> videoMap = videoMapper.selectBatchIds(videoIds).stream()
				.filter(video -> VideoStatus.PUBLISHED.name().equals(video.getStatus()))
				.collect(Collectors.toMap(Video::getId, Function.identity()));
		List<VideoDetailResponse> records = videoIds.stream()
				.map(videoMap::get)
				.filter(java.util.Objects::nonNull)
				.map(VideoDetailResponse::from)
				.toList();
		long pages = (total + request.getPageSize() - 1) / request.getPageSize();
		return new PageResponse<>(records, total, request.getPageNo(), request.getPageSize(), pages);
	}

	public PageResponse<VideoDetailResponse> searchPublishedFromDatabase(String keyword, PageRequest request) {
		Page<Video> page = new Page<>(request.getPageNo(), request.getPageSize());
		LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>()
				.eq(Video::getStatus, VideoStatus.PUBLISHED.name())
				.and(StringUtils.hasText(keyword), condition -> condition
						.like(Video::getTitle, keyword)
						.or()
						.like(Video::getDescription, keyword))
				.orderByDesc(Video::getCreatedAt);
		return PageResponse.from(videoMapper.selectPage(page, wrapper), VideoDetailResponse::from);
	}

	public List<Video> listAllPublishedForIndex() {
		return videoMapper.selectList(new LambdaQueryWrapper<Video>()
				.eq(Video::getStatus, VideoStatus.PUBLISHED.name())
				.orderByAsc(Video::getId));
	}

	@Transactional
	public void markTranscoding(Long videoId) {
		Video video = getById(videoId);
		video.setStatus(VideoStatus.TRANSCODING.name());
		videoMapper.updateById(video);
	}

	@Transactional
	public void markPublished(Long videoId, String hlsMasterObjectKey, Integer durationSeconds,
			Integer width, Integer height, String coverObjectKey) {
		Video video = getById(videoId);
		video.setStatus(VideoStatus.PUBLISHED.name());
		video.setHlsMasterObjectKey(hlsMasterObjectKey);
		video.setDurationSeconds(durationSeconds);
		video.setWidth(width);
		video.setHeight(height);
		video.setCoverObjectKey(coverObjectKey);
		videoMapper.updateById(video);
	}

	@Transactional
	public void markFailed(Long videoId) {
		Video video = getById(videoId);
		video.setStatus(VideoStatus.FAILED.name());
		videoMapper.updateById(video);
	}

	@Transactional
	public void incrementLikeCount(Long videoId) {
		updateCounter(videoId, "like_count = like_count + 1");
	}

	@Transactional
	public void decrementLikeCount(Long videoId) {
		updateCounter(videoId, "like_count = CASE WHEN like_count > 0 THEN like_count - 1 ELSE 0 END");
	}

	@Transactional
	public void incrementCollectCount(Long videoId) {
		updateCounter(videoId, "collect_count = collect_count + 1");
	}

	@Transactional
	public void decrementCollectCount(Long videoId) {
		updateCounter(videoId, "collect_count = CASE WHEN collect_count > 0 THEN collect_count - 1 ELSE 0 END");
	}

	@Transactional
	public void incrementCommentCount(Long videoId) {
		updateCounter(videoId, "comment_count = comment_count + 1");
	}

	@Transactional
	public void incrementPlayCount(Long videoId) {
		incrementPlayCount(videoId, 1L);
	}

	@Transactional
	public void incrementPlayCount(Long videoId, long delta) {
		if (delta <= 0) {
			return;
		}
		updateCounter(videoId, "play_count = play_count + " + delta);
	}

	private void updateCounter(Long videoId, String sqlSet) {
		LambdaUpdateWrapper<Video> wrapper = new LambdaUpdateWrapper<Video>()
				.eq(Video::getId, videoId)
				.setSql(sqlSet);
		if (videoMapper.update(null, wrapper) != 1) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "video not found");
		}
	}

	public VideoResponse toResponse(Video video) {
		return VideoResponse.from(video);
	}

	public VideoDetailResponse toDetailResponse(Video video) {
		return VideoDetailResponse.from(video);
	}
}
