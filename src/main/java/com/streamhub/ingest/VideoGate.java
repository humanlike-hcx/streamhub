package com.streamhub.ingest;

import java.util.concurrent.CompletableFuture;

/** "这个视频现在能不能发弹幕"。返回 Future：查库是慢操作，不能卡住 Netty IO 线程。 */
public interface VideoGate {
    CompletableFuture<Boolean> isOpen(long videoId);
}
