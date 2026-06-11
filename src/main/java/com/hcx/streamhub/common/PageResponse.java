package com.hcx.streamhub.common;

import java.util.List;
import java.util.function.Function;

import com.baomidou.mybatisplus.core.metadata.IPage;

public record PageResponse<T>(
		List<T> records,
		long total,
		long pageNo,
		long pageSize,
		long pages) {

	public static <S, T> PageResponse<T> from(IPage<S> page, Function<S, T> mapper) {
		return new PageResponse<>(
				page.getRecords().stream().map(mapper).toList(),
				page.getTotal(),
				page.getCurrent(),
				page.getSize(),
				page.getPages());
	}
}
