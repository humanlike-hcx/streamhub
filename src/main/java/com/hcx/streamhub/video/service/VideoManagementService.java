package com.hcx.streamhub.video.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.hcx.streamhub.comment.service.VideoCommentService;
import com.hcx.streamhub.interaction.service.VideoInteractionService;
import com.hcx.streamhub.search.service.VideoSearchService;
import com.hcx.streamhub.transcode.service.TranscodeTaskService;
import com.hcx.streamhub.upload.service.MinioStorageService;
import com.hcx.streamhub.video.dto.UpdateVideoRequest;
import com.hcx.streamhub.video.dto.VideoDetailResponse;
import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.enums.VideoStatus;

@Service
public class VideoManagementService {

	private final VideoService videoService;
	private final VideoViewService videoViewService;
	private final VideoSearchService videoSearchService;
	private final VideoInteractionService videoInteractionService;
	private final VideoCommentService videoCommentService;
	private final MinioStorageService minioStorageService;
	private final TranscodeTaskService transcodeTaskService;

	public VideoManagementService(VideoService videoService, VideoViewService videoViewService,
			VideoSearchService videoSearchService, VideoInteractionService videoInteractionService,
			VideoCommentService videoCommentService, MinioStorageService minioStorageService,
			TranscodeTaskService transcodeTaskService) {
		this.videoService = videoService;
		this.videoViewService = videoViewService;
		this.videoSearchService = videoSearchService;
		this.videoInteractionService = videoInteractionService;
		this.videoCommentService = videoCommentService;
		this.minioStorageService = minioStorageService;
		this.transcodeTaskService = transcodeTaskService;
	}

	@Transactional
	public VideoDetailResponse update(Long userId, Long videoId, UpdateVideoRequest request) {
		Video video = videoService.updateOwnedVideo(
				userId,
				videoId,
				request.title().trim(),
				StringUtils.hasText(request.description()) ? request.description().trim() : null);
		if (VideoStatus.PUBLISHED.name().equals(video.getStatus())) {
			videoSearchService.indexPublishedVideo(video);
		}
		return VideoDetailResponse.from(video);
	}

	@Transactional
	public void delete(Long userId, Long videoId) {
		Video video = videoService.softDeleteOwnedVideo(userId, videoId);
		transcodeTaskService.deleteByVideoId(videoId);
		videoInteractionService.deleteByVideoId(videoId);
		videoCommentService.deleteByVideoId(videoId);
		videoViewService.removeVideo(videoId);
		videoSearchService.deleteVideo(videoId);
		cleanupObjects(video);
	}

	private void cleanupObjects(Video video) {
		if (!videoService.hasOtherVideoUsingOriginalObject(video.getId(), video.getOriginalObjectKey())) {
			minioStorageService.deleteObjectQuietly(video.getOriginalObjectKey());
		}
		minioStorageService.deleteObjectQuietly(video.getCoverObjectKey());
		minioStorageService.deletePrefixQuietly(hlsPrefix(video.getHlsMasterObjectKey()));
	}

	private String hlsPrefix(String hlsMasterObjectKey) {
		if (!StringUtils.hasText(hlsMasterObjectKey)) {
			return null;
		}
		int index = hlsMasterObjectKey.lastIndexOf('/');
		if (index < 0) {
			return null;
		}
		return hlsMasterObjectKey.substring(0, index);
	}
}
