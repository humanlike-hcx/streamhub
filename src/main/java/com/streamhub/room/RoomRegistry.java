package com.streamhub.room;

import com.streamhub.common.Metrics;
import com.streamhub.common.StreamhubProperties;
import io.netty.channel.Channel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本节点上所有房间。只管"本机"的连接：跨节点由 MQ 广播消费解决，
 * 每个节点各自收到全部弹幕，再调用 publish 推给自己的观众。
 */
@Component
public class RoomRegistry {
    private final ConcurrentHashMap<Long, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final StreamhubProperties.Room cfg;
    private final Metrics metrics;

    @Autowired
    public RoomRegistry(StreamhubProperties props, Metrics metrics) {
        this(props.room(), metrics);
    }

    public RoomRegistry(StreamhubProperties.Room cfg, Metrics metrics) {
        this.cfg = cfg;
        this.metrics = metrics;
    }

    /** 连接握手成功后调用，必须在该连接自己的 EventLoop 线程里。 */
    public void join(long videoId, Channel ch) {
        // compute 对同一个 key 是串行的：加人和"人数归零删房间"不会交错
        Room room = rooms.compute(videoId, (id, r) -> {
            Room target = r == null ? new Room(id, cfg, metrics) : r;
            target.members.incrementAndGet();
            return target;
        });
        room.shard(ch.eventLoop()).add(ch);
        connections.incrementAndGet();
    }

    /** 连接断开时调用，同样在该连接的 EventLoop 线程里。 */
    public void leave(long videoId, Channel ch) {
        Room room = rooms.get(videoId);
        if (room == null) {
            return;
        }
        room.shard(ch.eventLoop()).remove(ch);
        connections.decrementAndGet();
        rooms.computeIfPresent(videoId, (id, r) -> r.members.decrementAndGet() <= 0 ? null : r);
    }

    /**
     * 把一条弹幕推给本机这个房间的所有观众。可以从任意线程调用（MQ 消费线程）：
     * 我们只是把任务丢给每个分片自己的 EventLoop，真正的合并和写在那个线程里做。
     */
    public void publish(long videoId, String itemJson) {
        Room room = rooms.get(videoId);
        if (room == null) {
            return;   // 本机没人看这个视频，直接忽略（落库由另一个消费组负责，不受影响）
        }
        for (RoomShard shard : room.shards()) {
            shard.loop.execute(() -> shard.append(itemJson));
        }
    }

    /** 每秒检查一次在线人数，变了才推，避免进出房间时给所有人刷屏。 */
    @Scheduled(fixedRate = 1000)
    public void pushOnlineCounts() {
        for (Room room : rooms.values()) {
            int n = room.members.get();
            if (n == room.lastReportedOnline) {
                continue;
            }
            room.lastReportedOnline = n;
            String text = "{\"op\":\"online\",\"count\":" + n + "}";
            for (RoomShard shard : room.shards()) {
                shard.loop.execute(() -> shard.writeToAll(text));
            }
        }
    }

    public int connectionCount() {
        return connections.get();
    }

    public int roomCount() {
        return rooms.size();
    }
}
