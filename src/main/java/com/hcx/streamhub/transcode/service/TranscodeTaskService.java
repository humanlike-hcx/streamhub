package com.hcx.streamhub.transcode.service;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.transcode.entity.TranscodeTask;
import com.hcx.streamhub.transcode.enums.TranscodeTaskStatus;
import com.hcx.streamhub.transcode.mapper.TranscodeTaskMapper;

@Service
public class TranscodeTaskService {

	private static final int DEFAULT_MAX_RETRY_COUNT = 3;

	private final TranscodeTaskMapper transcodeTaskMapper;

	public TranscodeTaskService(TranscodeTaskMapper transcodeTaskMapper) {
		this.transcodeTaskMapper = transcodeTaskMapper;
	}

	public TranscodeTask createWaitingTask(Long videoId) {
		TranscodeTask task = new TranscodeTask();
		task.setVideoId(videoId);
		task.setStatus(TranscodeTaskStatus.WAITING.name());
		task.setRetryCount(0);
		task.setMaxRetryCount(DEFAULT_MAX_RETRY_COUNT);
		transcodeTaskMapper.insert(task);
		return task;
	}

	public TranscodeTask getById(Long taskId) {
		TranscodeTask task = transcodeTaskMapper.selectById(taskId);
		if (task == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "transcode task not found");
		}
		return task;
	}

	@Transactional
	public boolean tryStartTask(Long taskId) {
		TranscodeTask update = new TranscodeTask();
		update.setStatus(TranscodeTaskStatus.PROCESSING.name());
		update.setStartedAt(LocalDateTime.now());
		LambdaUpdateWrapper<TranscodeTask> wrapper = new LambdaUpdateWrapper<TranscodeTask>()
				.eq(TranscodeTask::getId, taskId)
				.eq(TranscodeTask::getStatus, TranscodeTaskStatus.WAITING.name());
		return transcodeTaskMapper.update(update, wrapper) == 1;
	}

	@Transactional
	public void markSuccess(Long taskId) {
		TranscodeTask task = getById(taskId);
		task.setStatus(TranscodeTaskStatus.SUCCESS.name());
		task.setLastErrorMessage(null);
		task.setFinishedAt(LocalDateTime.now());
		transcodeTaskMapper.updateById(task);
	}

	@Transactional
	public TranscodeFailureResult markFailedOrWaitingForRetry(Long taskId, String errorMessage) {
		TranscodeTask task = getById(taskId);
		int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
		int maxRetryCount = task.getMaxRetryCount() == null ? DEFAULT_MAX_RETRY_COUNT : task.getMaxRetryCount();
		int nextRetryCount = retryCount + 1;

		task.setRetryCount(nextRetryCount);
		task.setMaxRetryCount(maxRetryCount);
		task.setLastErrorMessage(errorMessage);
		if (nextRetryCount < maxRetryCount) {
			task.setStatus(TranscodeTaskStatus.WAITING.name());
			task.setFinishedAt(null);
		}
		else {
			task.setStatus(TranscodeTaskStatus.FAILED.name());
			task.setFinishedAt(LocalDateTime.now());
		}

		if (transcodeTaskMapper.updateById(task) != 1) {
			throw new IllegalStateException("failed to update transcode task failure state, taskId=" + taskId);
		}
		return new TranscodeFailureResult(task, nextRetryCount < maxRetryCount);
	}

	public record TranscodeFailureResult(TranscodeTask task, boolean shouldRetry) {
	}

	@Transactional
	public void deleteByVideoId(Long videoId) {
		transcodeTaskMapper.delete(new LambdaQueryWrapper<TranscodeTask>()
				.eq(TranscodeTask::getVideoId, videoId));
	}
}
