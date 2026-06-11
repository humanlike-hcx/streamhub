package com.hcx.streamhub.common;

import java.io.Serializable;

import lombok.Getter;

@Getter
public class Result<T> implements Serializable {

	private static final long serialVersionUID = 1L;

	private final int code;
	private final String message;
	private final T data;

	private Result(int code, String message, T data) {
		this.code = code;
		this.message = message;
		this.data = data;
	}

	public static <T> Result<T> success() {
		return success(null);
	}

	public static <T> Result<T> success(T data) {
		return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
	}

	public static <T> Result<T> failure(ErrorCode errorCode) {
		return failure(errorCode, errorCode.getMessage());
	}

	public static <T> Result<T> failure(ErrorCode errorCode, String message) {
		return new Result<>(errorCode.getCode(), message, null);
	}
}
