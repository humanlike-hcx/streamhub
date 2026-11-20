-- V1：三张核心表。文件名 V1__xxx.sql 是 Flyway 的约定：V+版本号+两个下划线+描述。
-- 已执行过的脚本不能再改，要改表结构就新建 V2__xxx.sql。

-- 弹幕本体。主键 id 由服务端用雪花算法生成，同时充当幂等键：
-- 同一条消息被 MQ 重复投递时，INSERT IGNORE 会因为主键冲突直接跳过。
CREATE TABLE danmaku (
  id            BIGINT       NOT NULL PRIMARY KEY,
  video_id      BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  content       VARCHAR(255) NOT NULL,
  color         CHAR(7)      NOT NULL DEFAULT '#ffffff',
  playback_time DOUBLE       NOT NULL COMMENT '出现在视频第几秒',
  created_at    DATETIME(3)  NOT NULL,
  -- 历史查询按"某视频某段播放时间"取，所以索引是 (video_id, playback_time)
  KEY idx_video_play (video_id, playback_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 弹幕计数单独一张表：落库时只更新这里，不去锁视频表。
CREATE TABLE video_danmaku_stat (
  video_id   BIGINT      NOT NULL PRIMARY KEY,
  count      BIGINT      NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 视频的最小信息：本服务只关心"这个视频能不能发弹幕"。
CREATE TABLE video (
  id     BIGINT  NOT NULL PRIMARY KEY,
  status TINYINT NOT NULL COMMENT '1=开放弹幕 0=关闭'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
