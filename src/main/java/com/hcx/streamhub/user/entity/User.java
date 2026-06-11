package com.hcx.streamhub.user.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("users")
public class User {

	@TableId(type = IdType.AUTO)
	private Long id;

	private String username;

	private String passwordHash;

	private String nickname;

	private String avatarUrl;

	private String status;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	@TableLogic
	private Integer deleted;
}
