package com.hcx.streamhub.video.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("videos")
public class Video {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long userId;

	private String title;

	private String description;

	private String coverObjectKey;

	private String originalObjectKey;

	private String hlsMasterObjectKey;

	private String status;

	private Integer durationSeconds;

	private Integer width;

	private Integer height;

	private Long fileSize;

	private Long likeCount;

	private Long collectCount;

	private Long commentCount;

	private Long playCount;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	@TableLogic
	private Integer deleted;
}
