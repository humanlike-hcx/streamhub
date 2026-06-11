# StreamHub Project Guide

本项目是一个基于 Spring Boot 的视频内容平台，不包含 AI 功能。

## 技术栈

- Java 17
- Spring Boot 3.5.x
- Maven
- MyBatis-Plus
- MySQL
- Redis
- RabbitMQ
- MinIO
- FFmpeg
- OpenSearch，后期加入
- Vue3，后期加入
- Docker Compose

## 项目目标

实现一个视频网站后端系统，核心链路为：

用户上传视频 -> 保存原始文件到 MinIO -> 创建转码任务 -> RabbitMQ 异步消费 -> FFmpeg 转 HLS -> 上传 m3u8 和 ts 切片到 MinIO -> 更新视频状态 -> 前端播放。

## 代码规范

- Controller 只做参数接收、参数校验和结果返回
- 业务逻辑放在 Service
- 数据库访问放在 Mapper
- 所有接口返回统一 Result<T>
- 所有异常通过 GlobalExceptionHandler 统一处理
- 禁止在 Controller 中直接操作数据库
- 禁止把视频文件存进 MySQL
- 禁止把大段业务逻辑写进 MQ Consumer
- 新增功能必须补充必要注释
- 涉及数据库表变更时，必须同步修改 docs/sql/schema.sql

## 包结构

推荐包结构：

com.hcx.streamhub
├── common
├── config
├── auth
├── user
├── video
├── media
├── upload
├── transcode
├── interaction
├── comment
├── search
└── recommend

## 开发优先级

1. 基础工程结构
2. 用户注册登录
3. 视频元信息管理
4. MinIO 普通上传
5. RabbitMQ 异步转码
6. FFmpeg 生成 HLS
7. 点赞、收藏、评论
8. Redis 热门榜
9. OpenSearch 搜索
10. Docker Compose 一键启动