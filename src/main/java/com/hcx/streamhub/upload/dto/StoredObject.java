package com.hcx.streamhub.upload.dto;

public record StoredObject(String objectKey, long size, String contentType) {
}
