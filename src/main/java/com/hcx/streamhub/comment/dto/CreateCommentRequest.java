package com.hcx.streamhub.comment.dto;

import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.NotBlank;

public record CreateCommentRequest(
		@NotBlank
		@Length(max = 500)
		String content) {
}
