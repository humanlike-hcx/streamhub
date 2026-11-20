package com.streamhub.ingest;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.streamhub.common.StreamhubProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 视频状态的本地缓存（EchoFrame 每条弹幕都查一次 MySQL，这是它入口吞吐上不去的原因之一）。
 * - 命中缓存：Future 立刻完成，不碰数据库。
 * - 未命中：在单独的 video-loader 线程池里查库，IO 线程不等待。
 * - 30 秒过期：关闭弹幕后最多 30 秒生效，这是用一致性换性能的取舍。
 * - 不存在的视频也缓存 false，防止有人用随机 id 打穿到数据库。
 */
@Component
public class CachedVideoGate implements VideoGate {
    private final ExecutorService loader = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "video-loader");
        t.setDaemon(true);
        return t;
    });
    private final AsyncLoadingCache<Long, Boolean> cache;

    public CachedVideoGate(JdbcTemplate jdbc, StreamhubProperties props) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.ingest().videoCacheSeconds()))
                .maximumSize(100_000)
                .executor(loader)
                .buildAsync(videoId -> {
                    List<Integer> status = jdbc.queryForList("SELECT status FROM video WHERE id = ?", Integer.class, videoId);
                    return !status.isEmpty() && status.get(0) == 1;
                });
    }

    @Override
    public CompletableFuture<Boolean> isOpen(long videoId) {
        return cache.get(videoId);
    }

    @PreDestroy
    public void close() {
        loader.shutdownNow();
    }
}
