package com.hcx.streamhub.transcode.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hcx.streamhub.transcode.config.TranscodeProperties;
import com.hcx.streamhub.transcode.dto.TranscodeTaskMessage;
import com.hcx.streamhub.transcode.entity.TranscodeTask;
import com.hcx.streamhub.transcode.enums.TranscodeTaskStatus;
import com.hcx.streamhub.transcode.mq.TranscodeMessagePublisher;
import com.hcx.streamhub.search.service.VideoSearchService;
import com.hcx.streamhub.upload.service.MinioStorageService;
import com.hcx.streamhub.video.entity.Video;
import com.hcx.streamhub.video.service.VideoService;

@Service
public class VideoTranscodeService {

	private static final Logger log = LoggerFactory.getLogger(VideoTranscodeService.class);
	private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(\\.\\d+)?");

	private final TranscodeTaskService transcodeTaskService;
	private final VideoService videoService;
	private final MinioStorageService minioStorageService;
	private final TranscodeProperties transcodeProperties;
	private final TranscodeMessagePublisher transcodeMessagePublisher;
	private final VideoSearchService videoSearchService;

	public VideoTranscodeService(TranscodeTaskService transcodeTaskService, VideoService videoService,
			MinioStorageService minioStorageService, TranscodeProperties transcodeProperties,
			TranscodeMessagePublisher transcodeMessagePublisher, VideoSearchService videoSearchService) {
		this.transcodeTaskService = transcodeTaskService;
		this.videoService = videoService;
		this.minioStorageService = minioStorageService;
		this.transcodeProperties = transcodeProperties;
		this.transcodeMessagePublisher = transcodeMessagePublisher;
		this.videoSearchService = videoSearchService;
	}

	public void transcode(TranscodeTaskMessage message) {
		TranscodeTask task = transcodeTaskService.getById(message.taskId());
		if (!TranscodeTaskStatus.PROCESSING.name().equals(task.getStatus())) {
			log.info("Skip transcode task with status={}, taskId={}", task.getStatus(), task.getId());
			return;
		}

		Path workDirectory = Path.of(transcodeProperties.getWorkDir(), "task-" + task.getId() + "-" + UUID.randomUUID());
		try {
			Video video = videoService.getById(message.videoId());
			videoService.markTranscoding(video.getId());

			Path inputFile = workDirectory.resolve("original");
			Path hlsDirectory = workDirectory.resolve("hls");
			Path coverFile = workDirectory.resolve("cover.jpg");
			Files.createDirectories(hlsDirectory);

			minioStorageService.downloadToFile(video.getOriginalObjectKey(), inputFile);
			VideoMetadata metadata = probeMetadata(inputFile);
			extractCover(inputFile, coverFile);
			runFfmpeg(inputFile, hlsDirectory);

			String hlsMasterObjectKey = minioStorageService.uploadHlsDirectory(video.getId(), hlsDirectory);
			String coverObjectKey = minioStorageService.uploadCover(video.getId(), coverFile);
			videoService.markPublished(video.getId(), hlsMasterObjectKey,
					metadata.durationSeconds(), metadata.width(), metadata.height(), coverObjectKey);
			videoSearchService.indexPublishedVideo(videoService.getById(video.getId()));
			transcodeTaskService.markSuccess(task.getId());
			log.info("Transcode task completed, taskId={}, videoId={}", task.getId(), video.getId());
		}
		catch (Exception exception) {
			String messageText = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
			TranscodeTaskService.TranscodeFailureResult failureResult =
					transcodeTaskService.markFailedOrWaitingForRetry(task.getId(), messageText);
			TranscodeTask failedTask = failureResult.task();
			if (failureResult.shouldRetry()) {
				log.warn("Transcode task failed and will retry, taskId={}, videoId={}, retryCount={}, maxRetryCount={}, error={}",
						failedTask.getId(), message.videoId(), failedTask.getRetryCount(), failedTask.getMaxRetryCount(), messageText);
				transcodeMessagePublisher.publishDelayed(message, failedTask.getRetryCount());
				log.info("Republished RocketMQ transcode task after failure state update, taskId={}, videoId={}, retryCount={}",
						failedTask.getId(), message.videoId(), failedTask.getRetryCount());
			}
			else {
				videoService.markFailed(message.videoId());
				log.warn("Transcode task reached max retry count, taskId={}, videoId={}, retryCount={}, maxRetryCount={}, error={}",
						failedTask.getId(), message.videoId(), failedTask.getRetryCount(), failedTask.getMaxRetryCount(), messageText);
			}
		}
		finally {
			deleteQuietly(workDirectory);
		}
	}

	private void runFfmpeg(Path inputFile, Path hlsDirectory) throws IOException, InterruptedException {
		Path playlist = hlsDirectory.resolve("master.m3u8");
		Path segmentPattern = hlsDirectory.resolve("segment-%03d.ts");
		List<String> command = List.of(
				transcodeProperties.getFfmpegPath(),
				"-y",
				"-i", inputFile.toString(),
				"-codec:v", "libx264",
				"-codec:a", "aac",
				"-hls_time", "6",
				"-hls_playlist_type", "vod",
				"-hls_segment_filename", segmentPattern.toString(),
				playlist.toString());
		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IllegalStateException("ffmpeg failed with exit code " + exitCode + ": " + output);
		}
	}

	private VideoMetadata probeMetadata(Path inputFile) throws IOException, InterruptedException {
		List<String> command = List.of(
				transcodeProperties.getFfprobePath(),
				"-v", "error",
				"-select_streams", "v:0",
				"-show_entries", "stream=width,height:format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1",
				inputFile.toString());
		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IllegalStateException("ffprobe failed with exit code " + exitCode + ": " + output);
		}
		return parseMetadata(output);
	}

	private VideoMetadata parseMetadata(String output) {
		List<String> values = output.lines()
				.map(String::trim)
				.filter(value -> NUMBER_PATTERN.matcher(value).matches())
				.toList();
		if (values.size() < 3) {
			throw new IllegalStateException("ffprobe output is incomplete: " + output);
		}
		Integer width = Integer.valueOf(values.get(0));
		Integer height = Integer.valueOf(values.get(1));
		Integer durationSeconds = (int) Math.ceil(Double.parseDouble(values.get(2)));
		return new VideoMetadata(durationSeconds, width, height);
	}

	private void extractCover(Path inputFile, Path coverFile) throws IOException, InterruptedException {
		List<String> command = List.of(
				transcodeProperties.getFfmpegPath(),
				"-y",
				"-ss", "1",
				"-i", inputFile.toString(),
				"-frames:v", "1",
				"-q:v", "2",
				coverFile.toString());
		Process process = new ProcessBuilder(command)
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();
		if (exitCode != 0 || !Files.exists(coverFile)) {
			throw new IllegalStateException("extract cover failed with exit code " + exitCode + ": " + output);
		}
	}

	private void deleteQuietly(Path path) {
		if (path == null || !Files.exists(path)) {
			return;
		}
		try (var paths = Files.walk(path)) {
			paths.sorted((left, right) -> right.compareTo(left))
					.forEach(item -> {
						try {
							Files.deleteIfExists(item);
						}
						catch (IOException ignored) {
						}
					});
		}
		catch (IOException ignored) {
		}
	}

	private record VideoMetadata(Integer durationSeconds, Integer width, Integer height) {
	}
}
