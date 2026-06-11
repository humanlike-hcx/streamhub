package com.hcx.streamhub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest {

	@NotBlank
	@Size(min = 3, max = 64)
	private String username;

	@NotBlank
	@Size(min = 6, max = 64)
	private String password;

	@NotBlank
	@Size(max = 64)
	private String nickname;
}
