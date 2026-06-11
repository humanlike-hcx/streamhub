package com.hcx.streamhub.upload.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.hcx.streamhub.transcode.dto.TranscodeTaskMessage;
import com.hcx.streamhub.transcode.entity.TranscodeTask;
import com.hcx.streamhub.transcode.mq.TranscodeMessagePublisher;
import com.hcx.streamhub.transcode.service.TranscodeTaskService;
import com.hcx.streamhub.upload.dto.StoredObject;
import com.hcx.streamhub.video.dto.VideoResponse;
import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.service.VideoService;

@Service
public class VideoUploadService {

	private final MinioStorageService minioStorageService;
	private final VideoService videoService;
	private final TranscodeTaskService transcodeTaskService;
	private final TranscodeMessagePublisher transcodeMessagePublisher;

	public VideoUploadService(MinioStorageService minioStorageService, VideoService videoService,
			TranscodeTaskService transcodeTaskService, TranscodeMessagePublisher transcodeMessagePublisher) {
		this.minioStorageService = minioStorageService;
		this.videoService = videoService;
		this.transcodeTaskService = transcodeTaskService;
		this.transcodeMessagePublisher = transcodeMessagePublisher;
	}

	@Transactional
	public VideoResponse upload(Long userId, String title, String description, MultipartFile file) {
		StoredObject storedObject = minioStorageService.uploadOriginalVideo(userId, file);
		return createVideoAfterOriginalStored(userId, title, description, storedObject);
	}

	@Transactional
	public VideoResponse createVideoAfterOriginalStored(Long userId, String title, String description,
			StoredObject storedObject) {
		Video video = videoService.createWaitingVideo(
				userId,
				title,
				description,
				storedObject.objectKey(),
				storedObject.size());
		TranscodeTask task = transcodeTaskService.createWaitingTask(video.getId());
		publishAfterCommit(new TranscodeTaskMessage(task.getId(), video.getId()));
		return videoService.toResponse(video);
	}

	private void publishAfterCommit(TranscodeTaskMessage message) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			transcodeMessagePublisher.publish(message);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				transcodeMessagePublisher.publish(message);
			}
		});
	}
}
