package com.hcx.streamhub.upload.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hcx.streamhub.auth.security.AuthenticatedUser;
import com.hcx.streamhub.common.Result;
import com.hcx.streamhub.upload.dto.CompleteMultipartUploadRequest;
import com.hcx.streamhub.upload.dto.InitMultipartUploadRequest;
import com.hcx.streamhub.upload.dto.InitMultipartUploadResponse;
import com.hcx.streamhub.upload.dto.MultipartUploadProgressResponse;
import com.hcx.streamhub.upload.service.MultipartUploadService;
import com.hcx.streamhub.video.dto.VideoResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/videos/multipart")
public class MultipartUploadController {

	private final MultipartUploadService multipartUploadService;

	public MultipartUploadController(MultipartUploadService multipartUploadService) {
		this.multipartUploadService = multipartUploadService;
	}

	@PostMapping("/init")
	public Result<InitMultipartUploadResponse> init(
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@Valid @RequestBody InitMultipartUploadRequest request) {
		return Result.success(multipartUploadService.init(authenticatedUser.id(), request));
	}

	@PostMapping(value = "/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Result<Void> uploadChunk(
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@RequestParam String uploadId,
			@Min(0) @RequestParam Integer chunkIndex,
			@RequestPart MultipartFile file) {
		multipartUploadService.uploadChunk(authenticatedUser.id(), uploadId, chunkIndex, file);
		return Result.success();
	}

	@GetMapping("/{uploadId}")
	public Result<MultipartUploadProgressResponse> progress(
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@PathVariable String uploadId) {
		return Result.success(multipartUploadService.progress(authenticatedUser.id(), uploadId));
	}

	@PostMapping("/complete")
	public Result<VideoResponse> complete(
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@Valid @RequestBody CompleteMultipartUploadRequest request) {
		return Result.success(multipartUploadService.complete(authenticatedUser.id(), request));
	}
}
