package com.hcx.streamhub.upload.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.config.MinioProperties;
import com.hcx.streamhub.upload.dto.ObjectStream;
import com.hcx.streamhub.upload.dto.StoredObject;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

@Service
public class MinioStorageService {

	private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("mp4", "mov", "m4v", "mkv", "webm");

	private final MinioClient minioClient;
	private final MinioProperties minioProperties;

	public MinioStorageService(MinioClient minioClient, MinioProperties minioProperties) {
		this.minioClient = minioClient;
		this.minioProperties = minioProperties;
	}

	public StoredObject uploadOriginalVideo(Long userId, MultipartFile file) {
		validateVideoFile(file);
		String objectKey = buildOriginalVideoObjectKey(userId, file.getOriginalFilename());
		String contentType = resolveContentType(file);

		try {
			ensureBucketExists();
			try (InputStream inputStream = file.getInputStream()) {
				minioClient.putObject(PutObjectArgs.builder()
						.bucket(minioProperties.getBucketName())
						.object(objectKey)
						.stream(inputStream, file.getSize(), -1L)
						.contentType(contentType)
						.build());
			}
			return new StoredObject(objectKey, file.getSize(), contentType);
		}
		catch (BusinessException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new BusinessException(ErrorCode.VIDEO_UPLOAD_FAILED, "failed to upload video to MinIO");
		}
	}

	public void downloadToFile(String objectKey, Path targetPath) {
		try {
			Files.createDirectories(targetPath.getParent());
			try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
					.bucket(minioProperties.getBucketName())
					.object(objectKey)
					.build())) {
				Files.copy(inputStream, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (Exception exception) {
			throw new BusinessException(ErrorCode.VIDEO_UPLOAD_FAILED, "failed to download video from MinIO");
		}
	}

	public ObjectStream openObjectStream(String objectKey) {
		try {
			InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
					.bucket(minioProperties.getBucketName())
					.object(objectKey)
					.build());
			return new ObjectStream(inputStream, resolveHlsContentType(objectKey));
		}
		catch (Exception exception) {
			throw new BusinessException(ErrorCode.NOT_FOUND, "object not found");
		}
	}

	public String uploadHlsDirectory(Long videoId, Path hlsDirectory) {
		String hlsPrefix = "hls/%d/%s".formatted(videoId, UUID.randomUUID());
		try {
			ensureBucketExists();
			try (var files = Files.list(hlsDirectory)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					String filename = file.getFileName().toString();
					String objectKey = hlsPrefix + "/" + filename;
					try (InputStream inputStream = Files.newInputStream(file)) {
						minioClient.putObject(PutObjectArgs.builder()
								.bucket(minioProperties.getBucketName())
								.object(objectKey)
								.stream(inputStream, Files.size(file), -1L)
								.contentType(resolveHlsContentType(filename))
								.build());
					}
				}
			}
			return hlsPrefix + "/master.m3u8";
		}
		catch (Exception exception) {
			throw new BusinessException(ErrorCode.VIDEO_UPLOAD_FAILED, "failed to upload HLS files to MinIO");
		}
	}

	public String uploadCover(Long videoId, Path coverFile) {
		String objectKey = "cover/%d/%s.jpg".formatted(videoId, UUID.randomUUID());
		try {
			ensureBucketExists();
			try (InputStream inputStream = Files.newInputStream(coverFile)) {
				minioClient.putObject(PutObjectArgs.builder()
						.bucket(minioProperties.getBucketName())
						.object(objectKey)
						.stream(inputStream, Files.size(coverFile), -1L)
						.contentType("image/jpeg")
						.build());
			}
			return objectKey;
		}
		catch (Exception exception) {
			throw new BusinessException(ErrorCode.VIDEO_UPLOAD_FAILED, "failed to upload video cover to MinIO");
		}
	}

	private void validateVideoFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.VIDEO_FILE_EMPTY);
		}
		String extension = getExtension(file.getOriginalFilename());
		if (!SUPPORTED_EXTENSIONS.contains(extension)) {
			throw new BusinessException(ErrorCode.VIDEO_FILE_TYPE_UNSUPPORTED);
		}
	}

	private void ensureBucketExists() throws Exception {
		boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
				.bucket(minioProperties.getBucketName())
				.build());
		if (!exists) {
			minioClient.makeBucket(MakeBucketArgs.builder()
					.bucket(minioProperties.getBucketName())
					.build());
		}
	}

	private String buildOriginalVideoObjectKey(Long userId, String originalFilename) {
		String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
		String extension = getExtension(originalFilename);
		return "original/%d/%s/%s.%s".formatted(userId, datePath, UUID.randomUUID(), extension);
	}

	private String resolveContentType(MultipartFile file) {
		if (StringUtils.hasText(file.getContentType())) {
			return file.getContentType();
		}
		return "application/octet-stream";
	}

	private String getExtension(String filename) {
		String cleanFilename = StringUtils.cleanPath(filename == null ? "" : filename);
		int extensionIndex = cleanFilename.lastIndexOf('.');
		if (extensionIndex < 0 || extensionIndex == cleanFilename.length() - 1) {
			return "";
		}
		return cleanFilename.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
	}

	private String resolveHlsContentType(String filename) {
		if (filename.endsWith(".m3u8")) {
			return "application/vnd.apple.mpegurl";
		}
		if (filename.endsWith(".ts")) {
			return "video/mp2t";
		}
		if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (filename.endsWith(".png")) {
			return "image/png";
		}
		return "application/octet-stream";
	}
}
