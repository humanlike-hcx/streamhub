package com.hcx.streamhub.video.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.upload.dto.ObjectStream;
import com.hcx.streamhub.upload.service.MinioStorageService;
import com.hcx.streamhub.video.dto.VideoPlayResponse;
import com.hcx.streamhub.video.entity.Video;

@Service
public class VideoPlaybackService {

	private final VideoService videoService;
	private final MinioStorageService minioStorageService;
	private final VideoViewService videoViewService;

	public VideoPlaybackService(VideoService videoService, MinioStorageService minioStorageService,
			VideoViewService videoViewService) {
		this.videoService = videoService;
		this.minioStorageService = minioStorageService;
		this.videoViewService = videoViewService;
	}

	public VideoPlayResponse getPlayInfo(Long videoId) {
		Video video = videoService.getPlayableVideo(videoId);
		videoViewService.recordPlay(videoId);
		return new VideoPlayResponse(video.getId(), video.getStatus(), "/api/videos/%d/hls/master.m3u8".formatted(videoId));
	}

	public ObjectStream openHlsObject(Long videoId, String filename) {
		validateHlsFilename(filename);
		Video video = videoService.getPlayableVideo(videoId);
		String hlsPrefix = getHlsPrefix(video.getHlsMasterObjectKey());
		return minioStorageService.openObjectStream(hlsPrefix + "/" + filename);
	}

	public ObjectStream openCoverObject(Long videoId) {
		Video video = videoService.getById(videoId);
		if (video.getCoverObjectKey() == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "cover not found");
		}
		return minioStorageService.openObjectStream(video.getCoverObjectKey());
	}

	private void validateHlsFilename(String filename) {
		String cleanFilename = StringUtils.cleanPath(filename == null ? "" : filename);
		if (!cleanFilename.equals(filename) || cleanFilename.contains("/") || cleanFilename.contains("\\")) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "invalid HLS filename");
		}
		if (!filename.endsWith(".m3u8") && !filename.endsWith(".ts")) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "unsupported HLS filename");
		}
	}

	private String getHlsPrefix(String hlsMasterObjectKey) {
		int index = hlsMasterObjectKey.lastIndexOf('/');
		if (index < 0) {
			throw new BusinessException(ErrorCode.VIDEO_NOT_PLAYABLE);
		}
		return hlsMasterObjectKey.substring(0, index);
	}
}
