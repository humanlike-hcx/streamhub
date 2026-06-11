package com.hcx.streamhub.interaction.dto;

public record InteractionStatusResponse(
		Long videoId,
		boolean liked,
		boolean collected,
		Long likeCount,
		Long collectCount,
		Long commentCount) {
}
