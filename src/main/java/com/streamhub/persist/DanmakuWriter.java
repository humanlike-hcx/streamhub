package com.streamhub.persist;

import com.streamhub.common.DanmakuEvent;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把一批弹幕写进 MySQL（决策 3A），一个事务里完成：
 *   1. 按视频分组，每个视频一条多行 INSERT IGNORE —— 主键（雪花 id）冲突的行被跳过，这就是幂等；
 *      MySQL 返回的"影响行数"= 这次真正插进去的行数（重复的不算）。
 *   2. 计数只加"真正插进去的行数"：INSERT ... ON DUPLICATE KEY UPDATE count = count + n。
 * 插入和计数同一个事务：要么都成功，要么都回滚，计数永远等于实际行数。
 *
 * 不需要 SELECT ... FOR UPDATE 去锁视频行（EchoFrame 的做法），热门视频也不会排长队；
 * 计数行仍会被同一视频的并发事务短暂加锁，但事务很短。
 */
@Component
public class DanmakuWriter {
    private static final String INSERT_HEAD =
            "INSERT IGNORE INTO danmaku (id, video_id, user_id, content, color, playback_time, created_at) VALUES ";
    private static final String ROW = "(?,?,?,?,?,?,?)";
    private static final String COUNT_SQL =
            "INSERT INTO video_danmaku_stat (video_id, count, updated_at) VALUES (?, ?, NOW(3)) AS n "
                    + "ON DUPLICATE KEY UPDATE count = video_danmaku_stat.count + n.count, updated_at = n.updated_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DanmakuWriter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** @return 实际插入的行数（重复投递的消息不算） */
    public int write(List<DanmakuEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        Map<Long, List<DanmakuEvent>> byVideo = groupByVideo(events);
        Integer inserted = tx.execute(status -> {
            int total = 0;
            for (var entry : byVideo.entrySet()) {
                int n = jdbc.update(insertSql(entry.getValue().size()), insertArgs(entry.getValue()));
                if (n > 0) {
                    jdbc.update(COUNT_SQL, entry.getKey(), n);
                }
                total += n;
            }
            return total;
        });
        return inserted == null ? 0 : inserted;
    }

    /** TreeMap 按 videoId 排序：所有事务都按同样的顺序锁计数行，避免两个事务互相等对方（死锁）。 */
    static Map<Long, List<DanmakuEvent>> groupByVideo(List<DanmakuEvent> events) {
        Map<Long, List<DanmakuEvent>> byVideo = new TreeMap<>();
        for (DanmakuEvent e : events) {
            byVideo.computeIfAbsent(e.videoId(), k -> new ArrayList<>()).add(e);
        }
        return byVideo;
    }

    static String insertSql(int rows) {
        return INSERT_HEAD + String.join(",", Collections.nCopies(rows, ROW));
    }

    static Object[] insertArgs(List<DanmakuEvent> rows) {
        Object[] args = new Object[rows.size() * 7];
        int i = 0;
        for (DanmakuEvent e : rows) {
            args[i++] = e.id();
            args[i++] = e.videoId();
            args[i++] = e.userId();
            args[i++] = e.content();
            args[i++] = e.color();
            args[i++] = e.playbackTime();
            args[i++] = new Timestamp(e.sentAt());
        }
        return args;
    }
}
