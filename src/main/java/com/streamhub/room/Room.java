package com.streamhub.room;

import com.streamhub.common.Metrics;
import com.streamhub.common.StreamhubProperties;
import io.netty.channel.EventLoop;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 一个视频房间 = 若干分片（每个 EventLoop 一个）+ 本节点在线人数。 */
final class Room {
    final long videoId;
    /** 本节点上这个房间的连接数；只在 RoomRegistry 的 compute 里改，用来判断房间能不能删。 */
    final AtomicInteger members = new AtomicInteger();
    /** 上次推送给客户端的在线人数，避免人数没变也每秒推一次。 */
    volatile int lastReportedOnline = -1;
    private final ConcurrentHashMap<EventLoop, RoomShard> shards = new ConcurrentHashMap<>();
    private final StreamhubProperties.Room cfg;
    private final Metrics metrics;

    Room(long videoId, StreamhubProperties.Room cfg, Metrics metrics) {
        this.videoId = videoId;
        this.cfg = cfg;
        this.metrics = metrics;
    }

    RoomShard shard(EventLoop loop) {
        return shards.computeIfAbsent(loop, l -> new RoomShard(l, cfg, metrics));
    }

    Collection<RoomShard> shards() {
        return shards.values();
    }
}
