package com.hcx.streamhub.interaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hcx.streamhub.interaction.dto.InteractionStatusResponse;
import com.hcx.streamhub.interaction.entity.VideoCollect;
import com.hcx.streamhub.interaction.entity.VideoLike;
import com.hcx.streamhub.interaction.mapper.VideoCollectMapper;
import com.hcx.streamhub.interaction.mapper.VideoLikeMapper;
import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.service.VideoService;
import com.hcx.streamhub.video.service.VideoViewService;

@Service
public class VideoInteractionService {

	private final VideoLikeMapper videoLikeMapper;
	private final VideoCollectMapper videoCollectMapper;
	private final VideoService videoService;
	private final VideoViewService videoViewService;

	public VideoInteractionService(VideoLikeMapper videoLikeMapper, VideoCollectMapper videoCollectMapper,
			VideoService videoService, VideoViewService videoViewService) {
		this.videoLikeMapper = videoLikeMapper;
		this.videoCollectMapper = videoCollectMapper;
		this.videoService = videoService;
		this.videoViewService = videoViewService;
	}

	@Transactional
	public InteractionStatusResponse like(Long videoId, Long userId) {
		videoService.getPublishedVideo(videoId);
		if (!liked(videoId, userId)) {
			VideoLike like = new VideoLike();
			like.setVideoId(videoId);
			like.setUserId(userId);
			try {
				videoLikeMapper.insert(like);
				videoService.incrementLikeCount(videoId);
				videoViewService.increaseLikeScore(videoId);
			}
			catch (DuplicateKeyException ignored) {
				// Another request inserted the same like first. Treat it as idempotent success.
			}
		}
		return status(videoId, userId);
	}

	@Transactional
	public InteractionStatusResponse unlike(Long videoId, Long userId) {
		videoService.getPublishedVideo(videoId);
		int deleted = videoLikeMapper.delete(new LambdaQueryWrapper<VideoLike>()
				.eq(VideoLike::getVideoId, videoId)
				.eq(VideoLike::getUserId, userId));
		if (deleted > 0) {
			videoService.decrementLikeCount(videoId);
			videoViewService.decreaseLikeScore(videoId);
		}
		return status(videoId, userId);
	}

	@Transactional
	public InteractionStatusResponse collect(Long videoId, Long userId) {
		videoService.getPublishedVideo(videoId);
		if (!collected(videoId, userId)) {
			VideoCollect collect = new VideoCollect();
			collect.setVideoId(videoId);
			collect.setUserId(userId);
			try {
				videoCollectMapper.insert(collect);
				videoService.incrementCollectCount(videoId);
				videoViewService.increaseCollectScore(videoId);
			}
			catch (DuplicateKeyException ignored) {
				// Another request inserted the same collect first. Treat it as idempotent success.
			}
		}
		return status(videoId, userId);
	}

	@Transactional
	public InteractionStatusResponse uncollect(Long videoId, Long userId) {
		videoService.getPublishedVideo(videoId);
		int deleted = videoCollectMapper.delete(new LambdaQueryWrapper<VideoCollect>()
				.eq(VideoCollect::getVideoId, videoId)
				.eq(VideoCollect::getUserId, userId));
		if (deleted > 0) {
			videoService.decrementCollectCount(videoId);
			videoViewService.decreaseCollectScore(videoId);
		}
		return status(videoId, userId);
	}

	public InteractionStatusResponse status(Long videoId, Long userId) {
		Video video = videoService.getPublishedVideo(videoId);
		return new InteractionStatusResponse(
				video.getId(),
				liked(videoId, userId),
				collected(videoId, userId),
				video.getLikeCount(),
				video.getCollectCount(),
				video.getCommentCount());
	}

	private boolean liked(Long videoId, Long userId) {
		return videoLikeMapper.selectCount(new LambdaQueryWrapper<VideoLike>()
				.eq(VideoLike::getVideoId, videoId)
				.eq(VideoLike::getUserId, userId)) > 0;
	}

	private boolean collected(Long videoId, Long userId) {
		return videoCollectMapper.selectCount(new LambdaQueryWrapper<VideoCollect>()
				.eq(VideoCollect::getVideoId, videoId)
				.eq(VideoCollect::getUserId, userId)) > 0;
	}
}
