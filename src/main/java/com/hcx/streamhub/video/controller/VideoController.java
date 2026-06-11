package com.hcx.streamhub.video.controller;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.hibernate.validator.constraints.Length;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hcx.streamhub.auth.security.AuthenticatedUser;
import com.hcx.streamhub.common.PageRequest;
import com.hcx.streamhub.common.PageResponse;
import com.hcx.streamhub.common.Result;
import com.hcx.streamhub.upload.service.VideoUploadService;
import com.hcx.streamhub.upload.dto.ObjectStream;
import com.hcx.streamhub.video.dto.VideoDetailResponse;
import com.hcx.streamhub.video.dto.VideoPlayResponse;
import com.hcx.streamhub.video.dto.VideoResponse;
import com.hcx.streamhub.video.service.VideoPlaybackService;
import com.hcx.streamhub.video.service.VideoService;
import com.hcx.streamhub.video.service.VideoViewService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/videos")
public class VideoController {

	private final VideoUploadService videoUploadService;
	private final VideoPlaybackService videoPlaybackService;
	private final VideoService videoService;
	private final VideoViewService videoViewService;

	public VideoController(VideoUploadService videoUploadService, VideoPlaybackService videoPlaybackService,
			VideoService videoService, VideoViewService videoViewService) {
		this.videoUploadService = videoUploadService;
		this.videoPlaybackService = videoPlaybackService;
		this.videoService = videoService;
		this.videoViewService = videoViewService;
	}

	@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Result<VideoResponse> upload(
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@NotBlank @Length(max = 128) @RequestParam String title,
			@Length(max = 1024) @RequestParam(required = false) String description,
			@RequestPart MultipartFile file) {
		return Result.success(videoUploadService.upload(authenticatedUser.id(), title, description, file));
	}

	@GetMapping
	public Result<PageResponse<VideoDetailResponse>> list(@Valid PageRequest request) {
		return Result.success(videoService.listPublished(request));
	}

	@GetMapping("/my")
	public Result<PageResponse<VideoDetailResponse>> my(
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@Valid PageRequest request) {
		return Result.success(videoService.listByUser(authenticatedUser.id(), request));
	}

	@GetMapping("/hot")
	public Result<PageResponse<VideoDetailResponse>> hot(@Valid PageRequest request) {
		return Result.success(videoViewService.listHot(request));
	}

	@GetMapping("/{videoId}")
	public Result<VideoDetailResponse> detail(@PathVariable Long videoId) {
		return Result.success(videoService.toDetailResponse(videoService.getById(videoId)));
	}

	@GetMapping("/{videoId}/play")
	public Result<VideoPlayResponse> play(@PathVariable Long videoId) {
		return Result.success(videoPlaybackService.getPlayInfo(videoId));
	}

	@GetMapping("/{videoId}/hls/{filename}")
	public ResponseEntity<InputStreamResource> hls(@PathVariable Long videoId, @PathVariable String filename) {
		ObjectStream objectStream = videoPlaybackService.openHlsObject(videoId, filename);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(objectStream.contentType()))
				.body(new InputStreamResource(objectStream.inputStream()));
	}

	@GetMapping("/{videoId}/cover")
	public ResponseEntity<InputStreamResource> cover(@PathVariable Long videoId) {
		ObjectStream objectStream = videoPlaybackService.openCoverObject(videoId);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(objectStream.contentType()))
				.body(new InputStreamResource(objectStream.inputStream()));
	}
}
