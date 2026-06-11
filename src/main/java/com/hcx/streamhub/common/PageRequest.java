package com.hcx.streamhub.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PageRequest {

	@Min(1)
	private long pageNo = 1;

	@Min(1)
	@Max(100)
	private long pageSize = 10;

	public long offset() {
		return (pageNo - 1) * pageSize;
	}
}
