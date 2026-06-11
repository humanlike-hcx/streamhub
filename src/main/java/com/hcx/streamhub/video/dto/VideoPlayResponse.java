package com.hcx.streamhub.video.dto;

public record VideoPlayResponse(
		Long videoId,
		String status,
		String hlsMasterUrl) {
}
