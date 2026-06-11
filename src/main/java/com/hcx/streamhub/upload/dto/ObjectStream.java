package com.hcx.streamhub.upload.dto;

import java.io.InputStream;

public record ObjectStream(InputStream inputStream, String contentType) {
}
