package com.hcx.streamhub.interaction.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hcx.streamhub.auth.security.AuthenticatedUser;
import com.hcx.streamhub.common.Result;
import com.hcx.streamhub.interaction.dto.InteractionStatusResponse;
import com.hcx.streamhub.interaction.service.VideoInteractionService;

@Validated
@RestController
@RequestMapping("/api/videos/{videoId}")
public class VideoInteractionController {

	private final VideoInteractionService videoInteractionService;

	public VideoInteractionController(VideoInteractionService videoInteractionService) {
		this.videoInteractionService = videoInteractionService;
	}

	@GetMapping("/interaction")
	public Result<InteractionStatusResponse> status(
			@PathVariable Long videoId,
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		return Result.success(videoInteractionService.status(videoId, authenticatedUser.id()));
	}

	@PostMapping("/like")
	public Result<InteractionStatusResponse> like(
			@PathVariable Long videoId,
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		return Result.success(videoInteractionService.like(videoId, authenticatedUser.id()));
	}

	@DeleteMapping("/like")
	public Result<InteractionStatusResponse> unlike(
			@PathVariable Long videoId,
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		return Result.success(videoInteractionService.unlike(videoId, authenticatedUser.id()));
	}

	@PostMapping("/collect")
	public Result<InteractionStatusResponse> collect(
			@PathVariable Long videoId,
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		return Result.success(videoInteractionService.collect(videoId, authenticatedUser.id()));
	}

	@DeleteMapping("/collect")
	public Result<InteractionStatusResponse> uncollect(
			@PathVariable Long videoId,
			@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		return Result.success(videoInteractionService.uncollect(videoId, authenticatedUser.id()));
	}
}
