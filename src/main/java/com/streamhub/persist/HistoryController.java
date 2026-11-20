package com.streamhub.persist;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 历史弹幕：播放器拖到第 from 秒时，拉取 [from, to) 区间的弹幕。
 * 用到索引 (video_id, playback_time)，范围查询不需要扫全表。
 */
@RestController
public class HistoryController {
    private static final int MAX_LIMIT = 1000;
    private final JdbcTemplate jdbc;

    public HistoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/videos/{videoId}/danmaku")
    public List<Map<String, Object>> history(@PathVariable long videoId,
                                             @RequestParam(defaultValue = "0") double from,
                                             @RequestParam(defaultValue = "60") double to,
                                             @RequestParam(defaultValue = "500") int limit) {
        return jdbc.query("""
                        SELECT id, user_id, content, color, playback_time FROM danmaku
                        WHERE video_id = ? AND playback_time >= ? AND playback_time < ?
                        ORDER BY playback_time LIMIT ?""",
                (rs, i) -> Map.<String, Object>of(
                        "id", Long.toString(rs.getLong("id")),       // 字符串，避免 JS 精度丢失
                        "userId", Long.toString(rs.getLong("user_id")),
                        "content", rs.getString("content"),
                        "color", rs.getString("color"),
                        "playbackTime", rs.getDouble("playback_time")),
                videoId, from, to, Math.min(Math.max(limit, 1), MAX_LIMIT));
    }

    @GetMapping("/videos/{videoId}/stat")
    public Map<String, Object> stat(@PathVariable long videoId) {
        List<Long> count = jdbc.queryForList("SELECT count FROM video_danmaku_stat WHERE video_id = ?", Long.class, videoId);
        return Map.of("videoId", Long.toString(videoId), "count", count.isEmpty() ? 0L : count.get(0));
    }
}
