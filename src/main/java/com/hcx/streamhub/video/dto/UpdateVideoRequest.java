package com.hcx.streamhub.video.dto;

import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.NotBlank;

public record UpdateVideoRequest(
		@NotBlank @Length(max = 128) String title,
		@Length(max = 1024) String description) {
}
