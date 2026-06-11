package com.hcx.streamhub.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.hcx.streamhub.auth.dto.LoginRequest;
import com.hcx.streamhub.auth.dto.LoginResponse;
import com.hcx.streamhub.auth.dto.RegisterRequest;
import com.hcx.streamhub.auth.security.JwtTokenProvider;
import com.hcx.streamhub.common.BusinessException;
import com.hcx.streamhub.common.ErrorCode;
import com.hcx.streamhub.user.dto.UserResponse;
import com.hcx.streamhub.user.entity.User;
import com.hcx.streamhub.user.enums.UserStatus;
import com.hcx.streamhub.user.service.UserService;

@Service
public class AuthService {

	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;

	public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider) {
		this.userService = userService;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenProvider = jwtTokenProvider;
	}

	public UserResponse register(RegisterRequest request) {
		String passwordHash = passwordEncoder.encode(request.getPassword());
		User user = userService.createUser(request.getUsername(), passwordHash, request.getNickname());
		return UserResponse.from(user);
	}

	public LoginResponse login(LoginRequest request) {
		User user = userService.findByUsername(request.getUsername());
		if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.USERNAME_OR_PASSWORD_ERROR);
		}
		if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
			throw new BusinessException(ErrorCode.USER_DISABLED);
		}

		String token = jwtTokenProvider.generateToken(user);
		return new LoginResponse(token, "Bearer", jwtTokenProvider.getExpiration(), UserResponse.from(user));
	}
}
