# steamhub
基于 Spring Boot + RabbitMQ + FFmpeg + MinIO 实现视频网站系统，支持视频上传、异步转码、HLS 切片播放、视频搜索、点赞评论与播放历史等功能。通过 RabbitMQ 解耦上传与转码流程，使用 Redis 缓存视频热度和用户互动状态，降低高频读接口对 MySQL 的压力。
