package com.hcx.streamhub.interaction.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("video_likes")
public class VideoLike {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long videoId;

	private Long userId;

	private LocalDateTime createdAt;
}
