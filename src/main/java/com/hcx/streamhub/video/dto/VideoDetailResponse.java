package com.hcx.streamhub.video.dto;

import java.time.LocalDateTime;

import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.enums.VideoStatus;

public record VideoDetailResponse(
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
		boolean playable,
		String playUrl,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	public static VideoDetailResponse from(Video video) {
		return from(video, video.getPlayCount());
	}

	public static VideoDetailResponse from(Video video, Long playCount) {
		boolean playable = VideoStatus.PUBLISHED.name().equals(video.getStatus()) && video.getHlsMasterObjectKey() != null;
		return new VideoDetailResponse(
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
				playCount,
				playable,
				playable ? "/api/videos/%d/play".formatted(video.getId()) : null,
				video.getCreatedAt(),
				video.getUpdatedAt());
	}
}
