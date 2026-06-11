package com.hcx.streamhub.upload.dto;

import java.util.Set;

public record MultipartUploadProgressResponse(
		String uploadId,
		Integer totalChunks,
		Set<Integer> uploadedChunks,
		boolean completed) {
}
