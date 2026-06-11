package com.hcx.streamhub.auth.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hcx.streamhub.auth.dto.LoginRequest;
import com.hcx.streamhub.auth.dto.LoginResponse;
import com.hcx.streamhub.auth.dto.RegisterRequest;
import com.hcx.streamhub.auth.service.AuthService;
import com.hcx.streamhub.common.Result;
import com.hcx.streamhub.user.dto.UserResponse;

import jakarta.validation.Valid;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	public Result<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
		return Result.success(authService.register(request));
	}

	@PostMapping("/login")
	public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
		return Result.success(authService.login(request));
	}
}
