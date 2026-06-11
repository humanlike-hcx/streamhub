package com.hcx.streamhub.transcode.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("transcode_tasks")
public class TranscodeTask {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long videoId;

	private String status;

	private Integer retryCount;

	private Integer maxRetryCount;

	private String lastErrorMessage;

	private LocalDateTime startedAt;

	private LocalDateTime finishedAt;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;
}
