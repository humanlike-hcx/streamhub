-- 压测用视频：1-10 开放弹幕
INSERT INTO video (id, status) VALUES (1,1),(2,1),(3,1),(4,1),(5,1),(6,1),(7,1),(8,1),(9,1),(10,1) AS n ON DUPLICATE KEY UPDATE status = n.status;
