package com.hcx.streamhub.auth.dto;

import com.hcx.streamhub.user.dto.UserResponse;

public record LoginResponse(
		String token,
		String tokenType,
		long expiresIn,
		UserResponse user) {
}
