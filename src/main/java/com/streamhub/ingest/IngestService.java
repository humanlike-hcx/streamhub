package com.streamhub.ingest;

import com.streamhub.common.DanmakuEvent;
import com.streamhub.common.Metrics;
import com.streamhub.common.Protocol;
import com.streamhub.common.Protocol.ErrorCode;
import com.streamhub.common.StreamhubProperties;
import com.streamhub.common.id.SnowflakeIdGenerator;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 处理一条"发送弹幕"请求。整个过程不阻塞调用线程（Netty IO 线程）：
 *
 *   校验 → 用户限流 → 拿在途名额 → 查视频状态(缓存/异步) → 生成 ID → 异步发 MQ → 回调里回 ack
 *
 * 过载保护（背压）：在途请求（已发给 MQ 还没回复的）最多 maxInFlight 个，满了直接回 BUSY。
 * 和 EchoFrame 的区别：它是线程池队列满了抛异常、连接被关掉；我们只回一个错误，连接保留，
 * 客户端可以稍后重试。
 */
@Service
public class IngestService {
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    static final int MAX_CONTENT_CHARS = 100;

    private final VideoGate videos;
    private final DanmakuPublisher publisher;
    private final SnowflakeIdGenerator ids;
    private final Metrics metrics;
    private final UserRateLimiter limiter;
    private final Semaphore inFlight;
    private final int maxInFlight;
    private final LongSupplier clock;

    @Autowired
    public IngestService(VideoGate videos, DanmakuPublisher publisher, SnowflakeIdGenerator ids, Metrics metrics,
                         StreamhubProperties props) {
        this(videos, publisher, ids, metrics, props.ingest(), System::nanoTime, System::currentTimeMillis);
    }

    public IngestService(VideoGate videos, DanmakuPublisher publisher, SnowflakeIdGenerator ids, Metrics metrics,
                  StreamhubProperties.Ingest cfg, LongSupplier nanoClock, LongSupplier clock) {
        this.videos = videos;
        this.publisher = publisher;
        this.ids = ids;
        this.metrics = metrics;
        this.limiter = new UserRateLimiter(cfg.userRatePerSec(), cfg.userBurst(), nanoClock);
        this.maxInFlight = cfg.maxInFlight();
        this.inFlight = new Semaphore(maxInFlight);
        this.clock = clock;
    }

    public void submit(Channel ch, long userId, long videoId, SendRequest req) {
        metrics.received.increment();
        Long seq = req.clientSeq();
        String color = req.color() == null ? "#ffffff" : req.color();
        if (!valid(req, color)) {
            metrics.invalid.increment();
            reply(ch, Protocol.error(seq, ErrorCode.INVALID, "内容 1-" + MAX_CONTENT_CHARS + " 字，颜色 #rrggbb，播放时间 >= 0"));
            return;
        }
        if (!limiter.tryAcquire(userId)) {
            metrics.rateLimited.increment();
            reply(ch, Protocol.error(seq, ErrorCode.RATE_LIMITED, "发送太快了，歇一会儿"));
            return;
        }
        if (!inFlight.tryAcquire()) {   // tryAcquire 不等待：拿不到立刻拒绝，绝不让 IO 线程排队
            busy(ch, seq, "服务繁忙，请稍后重试");
            return;
        }
        // 从这里开始持有一个在途名额，下面每条结束路径都必须恰好释放一次
        Permit permit = new Permit();
        videos.isOpen(videoId).whenComplete((open, err) -> {
            if (err != null) {
                permit.release();
                busy(ch, seq, "视频状态暂时查不到，请稍后重试");
                return;
            }
            if (!open) {
                permit.release();
                metrics.videoClosed.increment();
                reply(ch, Protocol.error(seq, ErrorCode.VIDEO_CLOSED, "视频不存在或已关闭弹幕"));
                return;
            }
            long id;
            try {
                id = ids.nextId();
            } catch (IllegalStateException noWorkerId) {   // workerId 租约失效 / 时钟大幅回拨
                permit.release();
                busy(ch, seq, "服务繁忙，请稍后重试");
                return;
            }
            var event = new DanmakuEvent(id, videoId, userId, req.content(), color, req.playbackTime(),
                    clock.getAsLong());
            publisher.publish(event,
                    () -> {
                        permit.release();
                        metrics.acked.increment();
                        reply(ch, Protocol.ack(seq, id));
                    },
                    failure -> {
                        permit.release();
                        busy(ch, seq, "消息队列暂不可用，请稍后重试");
                    });
        });
    }

    public int inFlight() {
        return maxInFlight - inFlight.availablePermits();
    }

    private static boolean valid(SendRequest req, String color) {
        String c = req.content();
        Double t = req.playbackTime();
        return c != null && !c.isBlank() && c.length() <= MAX_CONTENT_CHARS
                && COLOR.matcher(color).matches()
                && t != null && Double.isFinite(t) && t >= 0;
    }

    private void busy(Channel ch, Long seq, String msg) {
        metrics.busy.increment();
        reply(ch, Protocol.error(seq, ErrorCode.BUSY, msg));
    }

    /** writeAndFlush 是线程安全的：从 MQ 回调线程调用时，Netty 会把写操作转交给连接自己的 EventLoop。 */
    private static void reply(Channel ch, String json) {
        if (ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /** 防止同一个名额被释放两次（回调被意外调两次时，信号量会"凭空多出"名额）。 */
    private final class Permit {
        private final AtomicBoolean released = new AtomicBoolean();

        void release() {
            if (released.compareAndSet(false, true)) {
                inFlight.release();
            }
        }
    }
}
