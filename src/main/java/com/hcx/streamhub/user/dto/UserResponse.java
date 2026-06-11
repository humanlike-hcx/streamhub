package com.hcx.streamhub.user.dto;

import com.hcx.streamhub.user.entity.User;

public record UserResponse(
		Long id,
		String username,
		String nickname,
		String avatarUrl,
		String status) {

	public static UserResponse from(User user) {
		return new UserResponse(
				user.getId(),
				user.getUsername(),
				user.getNickname(),
				user.getAvatarUrl(),
				user.getStatus());
	}
}
