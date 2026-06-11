package com.hcx.streamhub.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hcx.streamhub.comment.dto.CreateCommentRequest;
import com.hcx.streamhub.comment.dto.VideoCommentResponse;
import com.hcx.streamhub.comment.entity.VideoComment;
import com.hcx.streamhub.comment.mapper.VideoCommentMapper;
import com.hcx.streamhub.common.PageRequest;
import com.hcx.streamhub.common.PageResponse;
import com.hcx.streamhub.video.service.VideoService;
import com.hcx.streamhub.video.service.VideoViewService;

@Service
public class VideoCommentService {

	private final VideoCommentMapper videoCommentMapper;
	private final VideoService videoService;
	private final VideoViewService videoViewService;

	public VideoCommentService(VideoCommentMapper videoCommentMapper, VideoService videoService,
			VideoViewService videoViewService) {
		this.videoCommentMapper = videoCommentMapper;
		this.videoService = videoService;
		this.videoViewService = videoViewService;
	}

	public PageResponse<VideoCommentResponse> list(Long videoId, PageRequest request) {
		videoService.getPublishedVideo(videoId);
		Page<VideoComment> page = new Page<>(request.getPageNo(), request.getPageSize());
		LambdaQueryWrapper<VideoComment> wrapper = new LambdaQueryWrapper<VideoComment>()
				.eq(VideoComment::getVideoId, videoId)
				.orderByDesc(VideoComment::getCreatedAt);
		return PageResponse.from(videoCommentMapper.selectPage(page, wrapper), VideoCommentResponse::from);
	}

	@Transactional
	public VideoCommentResponse create(Long videoId, Long userId, CreateCommentRequest request) {
		videoService.getPublishedVideo(videoId);
		VideoComment comment = new VideoComment();
		comment.setVideoId(videoId);
		comment.setUserId(userId);
		comment.setContent(request.content().trim());
		videoCommentMapper.insert(comment);
		videoService.incrementCommentCount(videoId);
		videoViewService.increaseCommentScore(videoId);
		return VideoCommentResponse.from(comment);
	}

	@Transactional
	public void deleteByVideoId(Long videoId) {
		videoCommentMapper.delete(new LambdaQueryWrapper<VideoComment>()
				.eq(VideoComment::getVideoId, videoId));
	}
}
