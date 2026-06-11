package com.hcx.streamhub.comment.dto;

import java.time.LocalDateTime;

import com.hcx.streamhub.comment.entity.VideoComment;

public record VideoCommentResponse(
		Long id,
		Long videoId,
		Long userId,
		String content,
		LocalDateTime createdAt) {

	public static VideoCommentResponse from(VideoComment comment) {
		return new VideoCommentResponse(
				comment.getId(),
				comment.getVideoId(),
				comment.getUserId(),
				comment.getContent(),
				comment.getCreatedAt());
	}
}
