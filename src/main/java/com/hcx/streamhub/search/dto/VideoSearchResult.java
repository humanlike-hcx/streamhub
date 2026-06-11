package com.hcx.streamhub.search.dto;

import java.util.List;

public record VideoSearchResult(List<Long> videoIds, long total) {
}
