package com.hcx.streamhub.upload.dto;

import org.hibernate.validator.constraints.Length;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record InitMultipartUploadRequest(
		@NotBlank @Length(max = 128) String title,
		@Length(max = 1024) String description,
		@NotBlank @Length(max = 255) String fileName,
		@NotBlank @Pattern(regexp = "^[a-fA-F0-9]{32}$") String fileMd5,
		@NotNull @Min(1) Long fileSize,
		@NotNull @Min(1) Long chunkSize,
		@NotNull @Min(1) Integer totalChunks) {
}
