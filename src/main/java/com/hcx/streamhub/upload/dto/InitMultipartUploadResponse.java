package com.hcx.streamhub.upload.dto;

import java.util.Set;

import com.hcx.streamhub.video.dto.VideoResponse;

public record InitMultipartUploadResponse(
		String uploadId,
		boolean instantUploaded,
		Set<Integer> uploadedChunks,
		VideoResponse video) {
}
