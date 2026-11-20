package com.streamhub.persist;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamhub.common.DanmakuEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SQL 拼装和分组是纯逻辑，单测覆盖；真正写库的行为在压测（重复投递、kill -9）里验证。 */
class DanmakuWriterTest {
    private static DanmakuEvent e(long id, long video) {
        return new DanmakuEvent(id, video, 1, "x", "#ffffff", 1.0, 0);
    }

    @Test
    void groupsByVideoInAscendingOrderForConsistentLockOrder() {
        var groups = DanmakuWriter.groupByVideo(List.of(e(1, 30), e(2, 10), e(3, 30), e(4, 20)));
        assertThat(groups.keySet()).containsExactly(10L, 20L, 30L);
        assertThat(groups.get(30L)).extracting(DanmakuEvent::id).containsExactly(1L, 3L);
    }

    @Test
    void buildsOneMultiRowInsertIgnore() {
        String sql = DanmakuWriter.insertSql(3);
        assertThat(sql).startsWith("INSERT IGNORE INTO danmaku");
        assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(21);
        assertThat(DanmakuWriter.insertArgs(List.of(e(1, 1), e(2, 1), e(3, 1)))).hasSize(21);
    }
}
