package com.hcx.streamhub.comment.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hcx.streamhub.auth.security.AuthenticatedUser;
import com.hcx.streamhub.comment.dto.CreateCommentRequest;
import com.hcx.streamhub.comment.dto.VideoCommentResponse;
import com.hcx.streamhub.comment.service.VideoCommentService;
import com.hcx.streamhub.common.PageRequest;
import com.hcx.streamhub.common.PageResponse;
import com.hcx.streamhub.common.Result;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/videos/{videoId}/comments")
public class VideoCommentController {

	private final VideoCommentService videoCommentService;

	public VideoCommentController(VideoCommentService videoCommentService) {
		this.videoCommentService = videoCommentService;
	}

	@GetMapping
	public Result<PageResponse<VideoCommentResponse>> list(@PathVariable Long videoId, @Valid PageRequest request) {
		return Result.success(videoCommentService.list(videoId, request));
	}

	@PostMapping
	public Result<VideoCommentResponse> create(
			@PathVariable Long videoId,
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
			@Valid @RequestBody CreateCommentRequest request) {
		return Result.success(videoCommentService.create(videoId, authenticatedUser.id(), request));
	}
}
