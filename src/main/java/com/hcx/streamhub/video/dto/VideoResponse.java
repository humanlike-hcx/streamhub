package com.hcx.streamhub.video.dto;

import java.time.LocalDateTime;

import com.hcx.streamhub.video.entity.Video;

public record VideoResponse(
		Long id,
		Long userId,
		String title,
		String description,
		String coverUrl,
		String status,
		Integer duration,
		Integer width,
		Integer height,
		Long fileSize,
		Long likeCount,
		Long collectCount,
		Long commentCount,
		Long playCount,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	public static VideoResponse from(Video video) {
		return new VideoResponse(
				video.getId(),
				video.getUserId(),
				video.getTitle(),
				video.getDescription(),
				video.getCoverObjectKey() == null ? null : "/api/videos/%d/cover".formatted(video.getId()),
				video.getStatus(),
				video.getDurationSeconds(),
				video.getWidth(),
				video.getHeight(),
				video.getFileSize(),
				video.getLikeCount(),
				video.getCollectCount(),
				video.getCommentCount(),
				video.getPlayCount(),
				video.getCreatedAt(),
				video.getUpdatedAt());
	}
}
