package com.hcx.streamhub.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

	SUCCESS(0, "success"),
	BAD_REQUEST(400, "bad request"),
	UNAUTHORIZED(401, "unauthorized"),
	FORBIDDEN(403, "forbidden"),
	NOT_FOUND(404, "not found"),
	USERNAME_EXISTS(1001, "username already exists"),
	USERNAME_OR_PASSWORD_ERROR(1002, "username or password is incorrect"),
	USER_DISABLED(1003, "user is disabled"),
	TOKEN_INVALID(1004, "token is invalid"),
	VIDEO_FILE_EMPTY(2001, "video file is empty"),
	VIDEO_FILE_TYPE_UNSUPPORTED(2002, "video file type is unsupported"),
	VIDEO_UPLOAD_FAILED(2003, "video upload failed"),
	VIDEO_NOT_PLAYABLE(2004, "video is not playable"),
	INTERNAL_ERROR(500, "internal server error");

	private final int code;
	private final String message;

	ErrorCode(int code, String message) {
		this.code = code;
		this.message = message;
	}
}
