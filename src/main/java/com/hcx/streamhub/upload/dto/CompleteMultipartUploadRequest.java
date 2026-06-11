package com.hcx.streamhub.upload.dto;

import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.NotBlank;

public record CompleteMultipartUploadRequest(
		@NotBlank String uploadId,
		@NotBlank @Length(max = 128) String title,
		@Length(max = 1024) String description) {
}
