package com.streamhub.ingest;

/** 客户端 {"op":"send",...} 里的业务字段。clientSeq 是客户端自己的编号，回 ack/error 时原样带回。 */
public record SendRequest(Long clientSeq, String content, String color, Double playbackTime) {}
