package com.hcx.streamhub.comment.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("video_comments")
public class VideoComment {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long videoId;

	private Long userId;

	private String content;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	@TableLogic
	private Integer deleted;
}
