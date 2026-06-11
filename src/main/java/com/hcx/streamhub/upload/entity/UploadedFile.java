package com.hcx.streamhub.upload.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("uploaded_files")
public class UploadedFile {

	@TableId(type = IdType.AUTO)
	private Long id;

	private String fileMd5;

	private String fileName;

	private Long fileSize;

	private String contentType;

	private String bucketName;

	private String objectKey;

	private String status;

	private Long createdBy;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;
}
