package com.hcx.streamhub.transcode.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.transcode.dto.TranscodeTaskMessage;
import com.hcx.streamhub.transcode.entity.TranscodeTask;
import com.hcx.streamhub.transcode.enums.TranscodeTaskStatus;
import com.hcx.streamhub.transcode.service.TranscodeTaskService;
import com.hcx.streamhub.transcode.service.VideoTranscodeService;

@Component
public class TranscodeTaskConsumer {

	private static final Logger log = LoggerFactory.getLogger(TranscodeTaskConsumer.class);

	private final TranscodeTaskService transcodeTaskService;
	private final VideoTranscodeService videoTranscodeService;

	public TranscodeTaskConsumer(TranscodeTaskService transcodeTaskService, VideoTranscodeService videoTranscodeService) {
		this.transcodeTaskService = transcodeTaskService;
		this.videoTranscodeService = videoTranscodeService;
	}

	@RabbitListener(queues = TranscodeRabbitNames.QUEUE)
	public void consume(TranscodeTaskMessage message) {
		try {
			TranscodeTask task = transcodeTaskService.getById(message.taskId());
			if (!task.getVideoId().equals(message.videoId())) {
				log.warn("Ignore transcode message with mismatched video id, taskId={}, messageVideoId={}, taskVideoId={}",
						message.taskId(), message.videoId(), task.getVideoId());
				return;
			}
			if (!transcodeTaskService.tryStartTask(task.getId())) {
				TranscodeTask currentTask = transcodeTaskService.getById(task.getId());
				if (TranscodeTaskStatus.SUCCESS.name().equals(currentTask.getStatus())) {
					log.info("Skip duplicate transcode message because task already succeeded, taskId={}, videoId={}",
							currentTask.getId(), currentTask.getVideoId());
					return;
				}
				if (TranscodeTaskStatus.PROCESSING.name().equals(currentTask.getStatus())) {
					log.info("Skip duplicate transcode message because task is being processed, taskId={}, videoId={}",
							currentTask.getId(), currentTask.getVideoId());
					return;
				}
				if (TranscodeTaskStatus.FAILED.name().equals(currentTask.getStatus())) {
					log.info("Skip failed transcode task for now, taskId={}, videoId={}",
							currentTask.getId(), currentTask.getVideoId());
					return;
				}
				log.info("Skip transcode message because task status changed, taskId={}, status={}",
						currentTask.getId(), currentTask.getStatus());
				return;
			}

			log.info("Claimed transcode task, taskId={}, videoId={}", task.getId(), task.getVideoId());
			videoTranscodeService.transcode(message);
		}
		catch (BusinessException exception) {
			log.warn("Ignore transcode message because task does not exist, taskId={}, videoId={}",
					message.taskId(), message.videoId());
		}
	}
}
