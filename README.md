# Streamhub：实时弹幕服务

技术栈：Java 21、Spring Boot 3.5、Netty 4.1（WebSocket）、RocketMQ 5、MySQL 8、Redis 7。

- 设计文档：[`docs/design.md`](docs/design.md)
- 压测报告：[`docs/loadtest.md`](docs/loadtest.md)
- 大白话讲解和面试题：[`docs/explain.md`](docs/explain.md)

一句话：观众用 WebSocket 连进房间；有人发弹幕，服务端先把它可靠地写进 RocketMQ，再回复"发送成功"；
每个节点都订阅这条消息并推给自己的观众（攒 100ms 一起推）；另一个消费组把弹幕批量、去重地写进 MySQL。

## 架构

```
 观众/发送者 ──WebSocket──▶ 网关 Netty (gateway)
                              │  auth: 首条消息带 JWT
                              │  send: 校验 → 限流 → 在途名额 → 视频状态 → 雪花ID → 异步发 MQ → 回 ack
                              ▼
                     RocketMQ topic "danmaku"（8 个队列）
              ┌───────────────┴────────────────┐
   消费组 streamhub-push（广播）       消费组 streamhub-persist（集群）
   每个节点都收到全部消息             整个集群每条只处理一次
              ▼                                  ▼
   本机房间 RoomRegistry              DanmakuWriter：一个事务里
   按 EventLoop 分片、100ms/50条合并    多行 INSERT IGNORE + 计数 +n
   跳过写不动的慢客户端                         ▼
              ▼                                MySQL
          观众收到 batch 帧

   Redis：每个节点启动时抢一个雪花 workerId（streamhub:worker:{n}，60 秒租约，20 秒续一次）
```

| 包 | 干什么 |
|---|---|
| `gateway` | Netty WebSocket 服务器（端口 9100）、连接处理器 `WsHandler`、JWT 校验、开发用接口 |
| `ingest` | 发送链路：`IngestService`（限流/背压/ack）、`RocketMqPublisher`（异步发送）、`BroadcastConsumer`（广播消费 → 本机房间）、视频状态缓存 |
| `room` | 房间和分片：`RoomRegistry` / `Room` / `RoomShard`（合并推送、慢客户端跳过、在线人数） |
| `persist` | 落库：`PersistConsumer`（集群消费，批量）、`DanmakuWriter`（幂等写入）、历史查询接口 |
| `common` | 配置、指标 `/metrics`、协议 JSON、健康检查 `/health`、雪花 ID 和 workerId 租约 |

## 快速开始（Mac + Docker）

需要 JDK 21（`brew install --cask temurin@21`）和 Docker Desktop（给 4GB 以上内存）。不用装 Maven，项目自带 `./mvnw`。

```bash
docker compose up -d                       # MySQL / Redis / RocketMQ
docker compose ps                          # mysql 变成 healthy 再继续
# 建 topic（8 个队列；不建也会自动创建）
docker compose exec broker sh mqadmin updateTopic -n namesrv:9876 -c DefaultCluster -t danmaku -r 8 -w 8

./mvnw test                                # 单元测试，不需要中间件
DEV_ENDPOINTS=true ./mvnw spring-boot:run  # 启动；Flyway 自动建表

curl localhost:8080/health                 # {"status":"OK",...}
curl -X POST 'localhost:8080/dev/videos/1?status=1'     # 开一个视频（开发接口）
curl -X POST 'localhost:8080/dev/token?userId=42'       # 拿一个测试 token
```

> 说明：`docker-compose.yml` 是按 Mac 本地开发写的，但我这边的测试机没有 Docker，压测是在本机直接装的同版本中间件上跑的（见 `application-local.yml`），compose 文件本身还没在真机上验证过。

本机直接装中间件时：建好 `streamhub_local` 库和 `streamhub` 用户，用 `--spring.profiles.active=local` 启动（这个配置默认打开开发接口）。

### 用 WebSocket 试一下

```bash
# 需要 websocat：brew install websocat
websocat ws://127.0.0.1:9100/ws/room/1
{"op":"auth","token":"<上面拿到的 token>"}
{"op":"send","clientSeq":1,"content":"你好","color":"#ffffff","playbackTime":12.5}
```

你会依次收到：`{"op":"authOk",...}`、`{"op":"ack","clientSeq":1,"id":"..."}`，然后大约 100ms 内收到 `{"op":"batch","items":[...]}`，以及每秒变化时推送的 `{"op":"online","count":n}`。
不发 auth 也能连，只是游客只能看不能发。出错时只会收到 `{"op":"error","code":"RATE_LIMITED|BUSY|INVALID|VIDEO_CLOSED|UNAUTHORIZED",...}`，连接不会断。

### HTTP 接口

| 接口 | 作用 |
|---|---|
| `GET /health` | 依赖健康检查，有依赖挂了返回 503 |
| `GET /metrics` | 计数器：收到/ack/BUSY/限流、推送帧数、慢客户端跳过、落库行数、连接数、workerId |
| `GET /videos/{id}/danmaku?from=&to=&limit=` | 按播放时间查历史弹幕（limit 最多 1000） |
| `GET /videos/{id}/stat` | 弹幕总数 |
| `POST /dev/token?userId=`、`POST /dev/videos/{id}?status=` | 仅 `DEV_ENDPOINTS=true` 时存在 |

### 常用配置（环境变量）

| 变量 | 默认 | 含义 |
|---|---|---|
| `SERVER_PORT` / `WS_PORT` | 8080 / 9100 | HTTP / WebSocket 端口 |
| `STREAMHUB_NODE_ID` | 随机 | 节点名 |
| `STREAMHUB_WORKER_ID` | -1 | ≥0 时固定雪花 workerId，不用 Redis 抢 |
| `MERGE_WINDOW_MS` / `MERGE_MAX_ITEMS` | 100 / 50 | 合并窗口；0 = 关闭合并 |
| `USER_RATE` / `USER_BURST` | 5 / 10 | 每用户每秒条数 / 突发 |
| `MAX_IN_FLIGHT` | 2000 | 在途（发给 MQ 还没回）上限，超过回 BUSY |
| `PERSIST_BATCH` / `PERSIST_THREADS` | 200 / 4 | 落库批大小 / 线程数 |
| `JWT_SECRET` | 开发用密钥 | 生产环境必须换 |
| `ROCKETMQ_NAMESRV`、`DB_URL`、`DB_USER`、`DB_PASSWORD`、`REDIS_HOST` | 本地默认 | 中间件地址 |

起两个节点：`SERVER_PORT=8081 WS_PORT=9101 java -jar target/streamhub-0.1.0-SNAPSHOT.jar`，两个节点上的观众能互相看到对方节点发的弹幕。

## 压测结论（一台 8 核 16G 虚拟机，客户端和中间件同机，详见 docs/loadtest.md）

- 单房间 1000 观众：**3000 条/秒** 稳定（两轮 100% 投递，推送 P99 约 41ms，零错误）；4000 条/秒全部送达但 P99 到 0.4～1.2 秒；5000 条/秒机器打满。
- 合并推送：500 条/秒时写次数少 98%，CPU 少约 85%，代价是 P50 延迟从 4ms 变成 55ms。
- 单房间 2 万连接以内 P99 ≤ 161ms；3～4 万连接时机器（主要是压测客户端）打满，延迟到秒级，但不断连、不丢推送。
- 重复投递 3 次、压测中 kill -9：ack 过的弹幕零丢失、零重复，计数准确。
- 落库约 10 万行/秒；两节点跨节点投递 100%，ID 不冲突。

## 测试

`./mvnw test`：29 个单元测试，全部不依赖中间件（Redis 用内存假实现，Netty 用 `EmbeddedChannel`，MQ 用假的 publisher，时间可以手动拨）。CI 在 `.github/workflows/ci.yml`。

## 已知限制

- 在线人数、用户限流都是**单节点**的：两个节点时，同一用户最多能发 2 倍速率，在线人数只算本节点。
- JWT 没有注销/吊销机制，只能等过期。
- 视频状态缓存 30 秒，关闭弹幕后最多 30 秒内还能发。
- 实时推送是"尽力而为"：节点重启期间的广播消息不补发，超过 10 秒的旧消息直接丢弃（历史可以通过 HTTP 查）。落库不受影响。
- 慢客户端会被跳过帧（不会补发），这是用"个别人少看几条"换"整个房间不卡"。
- Flyway 11.7 会提示"MySQL 8.4 未经测试"，不影响使用。
- 压测都在一台共享机器上做，高负载段受机器上限约束。

## M0 面试题（附答题要点）

**1. 为什么用 Docker Compose 管理中间件，而不是直接装在本机？**
要点：一条命令就能启动或销毁，版本固定写在文件里，换台电脑也能一模一样地复现，团队成员的环境一致。缺点是 Docker 会多占内存，Mac 上的磁盘 IO 也会慢一些，所以压测数据要注明测试环境。

**2. Flyway 解决了什么问题？已经执行过的 V1 能改吗？**
要点：它把表结构变更也纳入了版本管理。应用启动时，Flyway 只执行还没执行过的脚本，并把执行记录写进 `flyway_schema_history` 表。已经执行过的脚本不能再改，因为 Flyway 会比对校验值，改了就会启动失败。要改表结构，就新建一个 `V2__xxx.sql`。

**3. `/health` 为什么在依赖挂掉时返回 503，而不是 200 加上一个错误字段？**
要点：负载均衡器和 k8s 的探针只看 HTTP 状态码。返回 503 时，它们会自动把这台实例从流量里摘掉，等它恢复后再加回来。

**4. 测试为什么不连真的数据库？**
要点：单元测试要快、要稳定，在 CI 上不能依赖外部服务。`HealthController` 只依赖 `DependencyCheck` 这个接口，测试里传一个假的实现就行，这就是依赖倒置的好处。真实连接数据库的集成测试，后面可以用 Testcontainers 单独来做。

**5. Maven Wrapper 有什么用？**
要点：项目自己指定 Maven 版本，第一次运行时自动下载，避免出现"在我电脑上能编译"这种版本不一致的问题。CI 上也用同一个版本。
