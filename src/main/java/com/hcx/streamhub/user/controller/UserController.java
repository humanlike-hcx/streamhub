package com.hcx.streamhub.user.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hcx.streamhub.auth.security.AuthenticatedUser;
import com.hcx.streamhub.common.Result;
import com.hcx.streamhub.user.dto.UserResponse;
import com.hcx.streamhub.user.service.UserService;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserService userService;

	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/me")
	public Result<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
		return Result.success(UserResponse.from(userService.getById(authenticatedUser.id())));
	}
}
