package com.hcx.streamhub.upload.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.config.MinioProperties;
import com.hcx.streamhub.upload.dto.CompleteMultipartUploadRequest;
import com.hcx.streamhub.upload.dto.InitMultipartUploadRequest;
import com.hcx.streamhub.upload.dto.InitMultipartUploadResponse;
import com.hcx.streamhub.upload.dto.MultipartUploadProgressResponse;
import com.hcx.streamhub.upload.dto.StoredObject;
import com.hcx.streamhub.upload.entity.UploadedFile;
import com.hcx.streamhub.upload.enums.UploadedFileStatus;
import com.hcx.streamhub.upload.mapper.UploadedFileMapper;
import com.hcx.streamhub.video.dto.VideoResponse;

@Service
public class MultipartUploadService {

	private static final Duration UPLOAD_TTL = Duration.ofHours(24);
	private static final String META_PREFIX = "streamhub:upload:";
	private static final String CHUNKS_SUFFIX = ":chunks";
	private static final String MERGE_LOCK_PREFIX = "streamhub:upload:merge:";

	private final UploadedFileMapper uploadedFileMapper;
	private final MinioStorageService minioStorageService;
	private final VideoUploadService videoUploadService;
	private final StringRedisTemplate stringRedisTemplate;
	private final RedissonClient redissonClient;
	private final MinioProperties minioProperties;

	public MultipartUploadService(UploadedFileMapper uploadedFileMapper, MinioStorageService minioStorageService,
			VideoUploadService videoUploadService, StringRedisTemplate stringRedisTemplate,
			RedissonClient redissonClient, MinioProperties minioProperties) {
		this.uploadedFileMapper = uploadedFileMapper;
		this.minioStorageService = minioStorageService;
		this.videoUploadService = videoUploadService;
		this.stringRedisTemplate = stringRedisTemplate;
		this.redissonClient = redissonClient;
		this.minioProperties = minioProperties;
	}

	public InitMultipartUploadResponse init(Long userId, InitMultipartUploadRequest request) {
		validateChunkPlan(request.fileSize(), request.chunkSize(), request.totalChunks());
		UploadedFile uploadedFile = findCompletedFile(request.fileMd5());
		if (uploadedFile != null) {
			VideoResponse video = videoUploadService.createVideoAfterOriginalStored(
					userId,
					request.title(),
					request.description(),
					new StoredObject(uploadedFile.getObjectKey(), uploadedFile.getFileSize(), uploadedFile.getContentType()));
			return new InitMultipartUploadResponse(null, true, Set.of(), video);
		}

		String uploadId = UUID.randomUUID().toString();
		String metaKey = metaKey(uploadId);
		var meta = stringRedisTemplate.opsForHash();
		meta.put(metaKey, "userId", userId.toString());
		meta.put(metaKey, "fileMd5", request.fileMd5().toLowerCase(Locale.ROOT));
		meta.put(metaKey, "fileName", request.fileName());
		meta.put(metaKey, "fileSize", request.fileSize().toString());
		meta.put(metaKey, "chunkSize", request.chunkSize().toString());
		meta.put(metaKey, "totalChunks", request.totalChunks().toString());
		meta.put(metaKey, "contentType", resolveContentType(request.fileName()));
		stringRedisTemplate.expire(metaKey, UPLOAD_TTL);
		stringRedisTemplate.expire(chunksKey(uploadId), UPLOAD_TTL);
		return new InitMultipartUploadResponse(uploadId, false, Set.of(), null);
	}

	public void uploadChunk(Long userId, String uploadId, Integer chunkIndex, MultipartFile file) {
		UploadMeta meta = getUploadMeta(uploadId);
		validateOwner(userId, meta);
		if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= meta.totalChunks()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "chunkIndex out of range");
		}
		minioStorageService.uploadMultipartChunk(uploadId, chunkIndex, file);
		stringRedisTemplate.opsForSet().add(chunksKey(uploadId), chunkIndex.toString());
		stringRedisTemplate.expire(chunksKey(uploadId), UPLOAD_TTL);
	}

	public MultipartUploadProgressResponse progress(Long userId, String uploadId) {
		UploadMeta meta = getUploadMeta(uploadId);
		validateOwner(userId, meta);
		Set<Integer> uploadedChunks = getUploadedChunks(uploadId);
		return new MultipartUploadProgressResponse(
				uploadId,
				meta.totalChunks(),
				uploadedChunks,
				uploadedChunks.size() == meta.totalChunks());
	}

	@Transactional
	public VideoResponse complete(Long userId, CompleteMultipartUploadRequest request) {
		UploadMeta meta = getUploadMeta(request.uploadId());
		validateOwner(userId, meta);
		Set<Integer> uploadedChunks = getUploadedChunks(request.uploadId());
		if (uploadedChunks.size() != meta.totalChunks()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "video chunks are not fully uploaded");
		}

		RLock lock = redissonClient.getLock(MERGE_LOCK_PREFIX + meta.fileMd5());
		boolean locked = false;
		Path mergedFile = null;
		try {
			locked = lock.tryLock(0, 10, TimeUnit.MINUTES);
			if (!locked) {
				throw new BusinessException(ErrorCode.BAD_REQUEST, "same file is being merged");
			}

			UploadedFile existingFile = findCompletedFile(meta.fileMd5());
			if (existingFile != null) {
				return createVideoFromUploadedFile(userId, request, existingFile);
			}

			mergedFile = minioStorageService.mergeChunksToTempFile(request.uploadId(), meta.totalChunks());
			String actualMd5 = calculateMd5(mergedFile);
			if (!meta.fileMd5().equalsIgnoreCase(actualMd5)) {
				throw new BusinessException(ErrorCode.BAD_REQUEST, "merged file md5 mismatch");
			}

			StoredObject storedObject = minioStorageService.uploadOriginalVideo(
					userId,
					meta.fileName(),
					mergedFile,
					meta.contentType());
			saveCompletedFile(userId, meta, storedObject);
			VideoResponse response = videoUploadService.createVideoAfterOriginalStored(
					userId,
					request.title(),
					request.description(),
					storedObject);
			cleanupUpload(request.uploadId(), meta.totalChunks());
			return response;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new BusinessException(ErrorCode.VIDEO_UPLOAD_FAILED, "failed to acquire merge lock");
		}
		finally {
			if (mergedFile != null) {
				deleteTempFile(mergedFile);
			}
			if (locked && lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}

	private VideoResponse createVideoFromUploadedFile(Long userId, CompleteMultipartUploadRequest request,
			UploadedFile uploadedFile) {
		return videoUploadService.createVideoAfterOriginalStored(
				userId,
				request.title(),
				request.description(),
				new StoredObject(uploadedFile.getObjectKey(), uploadedFile.getFileSize(), uploadedFile.getContentType()));
	}

	private void saveCompletedFile(Long userId, UploadMeta meta, StoredObject storedObject) {
		UploadedFile uploadedFile = new UploadedFile();
		uploadedFile.setFileMd5(meta.fileMd5().toLowerCase(Locale.ROOT));
		uploadedFile.setFileName(meta.fileName());
		uploadedFile.setFileSize(meta.fileSize());
		uploadedFile.setContentType(storedObject.contentType());
		uploadedFile.setBucketName(minioProperties.getBucketName());
		uploadedFile.setObjectKey(storedObject.objectKey());
		uploadedFile.setStatus(UploadedFileStatus.COMPLETED.name());
		uploadedFile.setCreatedBy(userId);
		try {
			uploadedFileMapper.insert(uploadedFile);
		}
		catch (DuplicateKeyException ignored) {
			// Another request may have completed the same MD5 first after lock expiry.
		}
	}

	private UploadedFile findCompletedFile(String fileMd5) {
		return uploadedFileMapper.selectOne(new LambdaQueryWrapper<UploadedFile>()
				.eq(UploadedFile::getFileMd5, fileMd5.toLowerCase(Locale.ROOT))
				.eq(UploadedFile::getStatus, UploadedFileStatus.COMPLETED.name())
				.last("LIMIT 1"));
	}

	private UploadMeta getUploadMeta(String uploadId) {
		String metaKey = metaKey(uploadId);
		var meta = stringRedisTemplate.opsForHash();
		Object userId = meta.get(metaKey, "userId");
		if (userId == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "upload task not found");
		}
		return new UploadMeta(
				Long.valueOf(userId.toString()),
				value(metaKey, "fileMd5"),
				value(metaKey, "fileName"),
				Long.valueOf(value(metaKey, "fileSize")),
				Long.valueOf(value(metaKey, "chunkSize")),
				Integer.valueOf(value(metaKey, "totalChunks")),
				value(metaKey, "contentType"));
	}

	private String value(String metaKey, String field) {
		Object value = stringRedisTemplate.opsForHash().get(metaKey, field);
		if (value == null) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "upload task metadata is incomplete");
		}
		return value.toString();
	}

	private void validateOwner(Long userId, UploadMeta meta) {
		if (!meta.userId().equals(userId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN, "upload task does not belong to current user");
		}
	}

	private void validateChunkPlan(Long fileSize, Long chunkSize, Integer totalChunks) {
		long expectedChunks = (fileSize + chunkSize - 1) / chunkSize;
		if (expectedChunks != totalChunks) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "totalChunks does not match fileSize and chunkSize");
		}
	}

	private Set<Integer> getUploadedChunks(String uploadId) {
		Set<String> chunks = stringRedisTemplate.opsForSet().members(chunksKey(uploadId));
		if (chunks == null || chunks.isEmpty()) {
			return Set.of();
		}
		return chunks.stream()
				.map(Integer::valueOf)
				.sorted(Comparator.naturalOrder())
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private String calculateMd5(Path file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			try (InputStream inputStream = Files.newInputStream(file)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = inputStream.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			StringBuilder builder = new StringBuilder();
			for (byte b : digest.digest()) {
				builder.append(String.format("%02x", b));
			}
			return builder.toString();
		}
		catch (Exception exception) {
			throw new BusinessException(ErrorCode.VIDEO_UPLOAD_FAILED, "failed to calculate merged file md5");
		}
	}

	private void cleanupUpload(String uploadId, int totalChunks) {
		minioStorageService.deleteMultipartChunks(uploadId, totalChunks);
		stringRedisTemplate.delete(metaKey(uploadId));
		stringRedisTemplate.delete(chunksKey(uploadId));
	}

	private void deleteTempFile(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (Exception ignored) {
			// Local temp file cleanup is best-effort.
		}
	}

	private String resolveContentType(String fileName) {
		String lowerName = StringUtils.hasText(fileName) ? fileName.toLowerCase(Locale.ROOT) : "";
		if (lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v")) {
			return "video/mp4";
		}
		if (lowerName.endsWith(".mov")) {
			return "video/quicktime";
		}
		if (lowerName.endsWith(".webm")) {
			return "video/webm";
		}
		if (lowerName.endsWith(".mkv")) {
			return "video/x-matroska";
		}
		return "application/octet-stream";
	}

	private String metaKey(String uploadId) {
		return META_PREFIX + uploadId + ":meta";
	}

	private String chunksKey(String uploadId) {
		return META_PREFIX + uploadId + CHUNKS_SUFFIX;
	}

	private record UploadMeta(
			Long userId,
			String fileMd5,
			String fileName,
			Long fileSize,
			Long chunkSize,
			Integer totalChunks,
			String contentType) {
	}
}
