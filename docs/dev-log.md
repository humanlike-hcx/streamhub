# StreamHub Development Log

> 这个文件用于保存每次开发进度、关键实现说明和面试复习点，避免聊天记录丢失后无法续接。

## 2026-06-09 当前代码盘点

### 当前已具备

- 基础 Spring Boot 工程已经建立，技术栈为 Java 17、Spring Boot 3.5.0、Maven。
- `pom.xml` 已引入 Web、Validation、Security、MyBatis-Plus、MySQL、Redis、RabbitMQ、MinIO、JWT、Lombok 等依赖。
- 已有统一返回体 `Result<T>`、业务异常 `BusinessException`、错误码 `ErrorCode`、全局异常处理器 `GlobalExceptionHandler`。
- 已有 JWT 登录注册相关包：`auth`、`user`，并配置了 Spring Security 无状态认证。
- 数据库脚本 `docs/sql/schema.sql` 已包含：
  - `users` 用户表
  - `videos` 视频元信息表
  - `transcode_tasks` 转码任务表
- `video` 包已有：
  - `Video` 实体，对应 `videos` 表。
  - `VideoMapper`。
  - `VideoService#createWaitingVideo(...)`，用于创建 `WAITING` 状态的视频元数据。
  - `VideoResponse`，用于接口响应时隐藏内部字段。

### 当前未完成

- 暂未发现基础视频文件上传接口：
  - 没有 `MultipartFile` 使用记录。
  - 没有 MinIO Client 配置类。
  - 没有上传 Controller。
  - `upload` 包目前只有 `package-info.java`。
- 暂未发现转码任务创建 Service/Mapper。
- 上传后还没有把原始文件写入 MinIO，也没有把视频记录和转码任务串起来。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
- 结果：构建成功，Spring Boot 上下文测试通过。
- 测试数量：1 个，通过 1 个。

### 下一步建议

1. 实现 MinIO 配置：
   - `MinioProperties`
   - `MinioConfig`
2. 实现上传业务：
   - Controller 接收 `MultipartFile`、标题、描述。
   - Service 校验文件、生成 object key、上传到 MinIO。
   - 调用 `VideoService#createWaitingVideo(...)` 保存视频元数据。
3. 实现转码任务基础表访问：
   - `TranscodeTask` 实体。
   - `TranscodeTaskMapper`。
   - `TranscodeTaskService#createWaitingTask(videoId)`。
4. 后续接 RabbitMQ：
   - 上传接口创建转码任务后发送消息。
   - Consumer 只做消息解析和调用 Service，避免写大段业务逻辑。

### 面试讲解点

- 为什么视频文件不能存 MySQL：
  - 数据库适合存结构化元数据，不适合存大二进制视频文件。
  - 视频文件会显著增加数据库体积、备份成本和 IO 压力。
  - 正确做法是文件存对象存储，如 MinIO、S3；MySQL 只保存 object key、状态、大小、时长等元数据。
- 为什么上传接口先创建 `WAITING` 状态：
  - 上传成功只代表原始文件已保存，不代表视频可以播放。
  - HLS 需要异步转码生成 `m3u8` 和 `ts` 切片，所以视频状态要体现处理流程。
  - 常见状态流转：`WAITING -> TRANSCODING -> READY`，失败则进入 `FAILED`。
- Controller、Service、Mapper 分层的原因：
  - Controller 只处理 HTTP 参数和返回结果。
  - Service 放业务编排和事务。
  - Mapper 只负责数据库访问。
  - 这样方便测试、维护，也避免接口层和数据层强耦合。
- 为什么转码应该异步：
  - FFmpeg 转码耗时长，不能阻塞用户上传请求。
  - 上传接口应该快速返回“已提交处理”。
  - RabbitMQ 可以削峰、重试、解耦上传和转码流程。

## 2026-06-09 MinIO 基础配置

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/config/MinioProperties.java`：
  - 使用 `@ConfigurationProperties(prefix = "minio")` 绑定 `application-dev.yml` 中的 `minio.*`。
  - 字段包括 `endpoint`、`accessKey`、`secretKey`、`bucketName`。
  - 使用 `@Validated` 和 `@NotBlank`，启动时即可发现关键配置缺失。
- 新增 `src/main/java/com/hcx/streamhub/config/MinioConfig.java`：
  - 使用 `@EnableConfigurationProperties(MinioProperties.class)` 注册配置属性类。
  - 暴露 `MinioClient` Bean，后续上传 Service 直接注入使用。
- 修改 `pom.xml`：
  - 新增 `okhttp.version`。
  - 显式加入 `com.squareup.okhttp3:okhttp-jvm`。

### 为什么这样设计

- `MinioProperties` 只负责配置绑定，不做业务逻辑。
  - 对应代码：`@ConfigurationProperties(prefix = "minio")`。
  - 面试讲法：把配置从业务代码中剥离，避免在上传逻辑里硬编码 endpoint、账号、bucket。
- `MinioConfig` 只负责创建第三方客户端 Bean。
  - 对应代码：`MinioClient.builder().endpoint(...).credentials(...).build()`。
  - 面试讲法：这是典型的 Spring 配置类职责，业务层只依赖 Bean，不关心客户端如何构造。
- 暂时没有在启动时自动创建 bucket。
  - 原因：自动创建 bucket 需要启动时连接 MinIO，如果本地 MinIO 未启动，会影响普通单元测试和应用上下文加载。
  - 后续更合适的位置是上传 Service 第一次上传前检查 bucket，或单独做一个可开关的初始化器。

### 依赖问题说明

- 使用 MinIO 9.0.1 时，编译 `MinioClient.builder().endpoint(...)` 曾报错：找不到 `okhttp3.HttpUrl`。
- 原因是 `com.squareup.okhttp3:okhttp:5.3.2` 在当前 Maven 环境中只有 Kotlin 多平台元数据，不包含 JVM class。
- 解决方式是在 `pom.xml` 中显式加入 `com.squareup.okhttp3:okhttp-jvm:5.3.2`。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
- 结果：构建成功，Spring Boot 上下文测试通过。
- 测试数量：1 个，通过 1 个。

### 下一步建议

1. 新增上传请求 DTO 或直接使用 `@RequestPart`/`@RequestParam` 接收表单字段。
2. 新增 `ObjectStorageService` 或 `MinioStorageService`，封装上传对象、生成 object key、后续检查 bucket。
3. 新增上传 Controller：
   - 接收 `MultipartFile`。
   - 调用上传 Service。
   - 调用 `VideoService#createWaitingVideo(...)` 保存元数据。
4. 新增转码任务实体和 Service，为 RabbitMQ 异步转码做准备。

## 2026-06-09 视频原始文件上传接口

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/video/controller/VideoController.java`：
  - 接口：`POST /api/videos/upload`
  - 请求类型：`multipart/form-data`
  - 参数：
    - `title`：必填，最长 128。
    - `description`：可选，最长 1024。
    - `file`：上传的视频文件。
  - 当前用户通过 `@AuthenticationPrincipal AuthenticatedUser authenticatedUser` 获取。
- 新增 `src/main/java/com/hcx/streamhub/upload/service/VideoUploadService.java`：
  - 负责上传业务编排。
  - 先调用 MinIO 存储服务保存原始文件。
  - 再调用 `VideoService#createWaitingVideo(...)` 写入视频元数据。
- 新增 `src/main/java/com/hcx/streamhub/upload/service/MinioStorageService.java`：
  - 校验文件不能为空。
  - 校验扩展名，当前支持 `mp4`、`mov`、`m4v`、`mkv`、`webm`。
  - 自动检查 bucket，不存在则创建。
  - 使用 `MinioClient#putObject(...)` 上传原始文件。
  - object key 规则：`original/{userId}/{yyyyMMdd}/{uuid}.{extension}`。
- 新增 `src/main/java/com/hcx/streamhub/upload/dto/StoredObject.java`：
  - 保存上传后的 object key、文件大小、content type。
- 修改 `src/main/java/com/hcx/streamhub/common/ErrorCode.java`：
  - 新增 `VIDEO_FILE_EMPTY`。
  - 新增 `VIDEO_FILE_TYPE_UNSUPPORTED`。
  - 新增 `VIDEO_UPLOAD_FAILED`。
- 修改 `src/main/resources/application-dev.yml`：
  - 增加 multipart 限制：
    - `spring.servlet.multipart.max-file-size=500MB`
    - `spring.servlet.multipart.max-request-size=520MB`
- 新增 `scripts/verify-upload.ps1`：
  - 用于本地验证注册、登录、上传接口。
- 新增 `scripts/stop-local-app.ps1`：
  - 用于停止本地临时启动的 `spring-boot:run` 进程。

### 代码链路讲解

- 入口在 `VideoController#upload(...)`。
  - Controller 只做 HTTP 参数接收、校验和统一返回。
  - 它不直接操作数据库，也不直接操作 MinIO。
- 核心编排在 `VideoUploadService#upload(...)`。
  - 先调用 `minioStorageService.uploadOriginalVideo(userId, file)`。
  - 再调用 `videoService.createWaitingVideo(...)`。
  - 返回 `VideoResponse` 给前端。
- MinIO 细节在 `MinioStorageService#uploadOriginalVideo(...)`。
  - `validateVideoFile(file)` 负责文件合法性校验。
  - `buildOriginalVideoObjectKey(...)` 负责生成对象存储路径。
  - `ensureBucketExists()` 负责保证 bucket 存在。
  - `minioClient.putObject(...)` 负责真正上传文件。
- 数据库写入仍然复用 `VideoService#createWaitingVideo(...)`。
  - `originalObjectKey` 存 MinIO object key。
  - `fileSize` 存文件大小。
  - `status` 固定为 `WAITING`，表示原始文件已上传，等待后续转码。

### 面试讲解点

- 这个接口体现了“文件和元数据分离”：
  - 原始视频文件存 MinIO。
  - MySQL 只保存 `original_object_key`、标题、描述、文件大小、状态等元数据。
- 为什么 `VideoController` 不直接调用 `MinioClient`：
  - Controller 直接操作第三方 SDK 会让 HTTP 层承担业务和基础设施细节。
  - 当前拆成 Controller、Upload Service、Storage Service 后，职责更清楚，后续替换对象存储也更容易。
- 为什么上传后状态是 `WAITING`：
  - 当前只完成了原始文件上传，还没有 FFmpeg 转 HLS。
  - 前端不能直接认为视频可播放。
  - 后续 RabbitMQ 转码消费成功后，状态才会更新为 `READY`。
- 当前事务边界：
  - MinIO 上传和 MySQL 插入不是同一个本地事务。
  - 目前基础版先保证主链路可用。
  - 后续可以在数据库写入失败时删除刚上传的 MinIO 对象，或通过定时任务清理无主对象。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用，并执行 `scripts/verify-upload.ps1`。
  - 上传接口返回 `code=0`。
  - 返回视频状态为 `WAITING`。
  - 返回文件大小为 `31`。
- 已查询 MySQL `videos` 表确认落库：
  - `title=Verification Video`
  - `status=WAITING`
  - `original_object_key=original/2/20260609/645fbe39-8fc8-4bdf-b9ea-65897523b410.mp4`

### 下一步建议

1. 实现转码任务实体、Mapper、Service。
2. 上传成功后创建 `transcode_tasks` 记录。
3. 再接 RabbitMQ：上传接口发送转码消息，Consumer 调用转码 Service。

## 2026-06-10 转码任务基础层

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/transcode/enums/TranscodeTaskStatus.java`：
  - `WAITING`：等待转码。
  - `PROCESSING`：转码处理中。
  - `SUCCESS`：转码成功。
  - `FAILED`：转码失败。
- 新增 `src/main/java/com/hcx/streamhub/transcode/entity/TranscodeTask.java`：
  - 对应数据库表 `transcode_tasks`。
  - 字段包括 `videoId`、`status`、`retryCount`、`errorMessage`、`startedAt`、`finishedAt` 等。
- 新增 `src/main/java/com/hcx/streamhub/transcode/mapper/TranscodeTaskMapper.java`：
  - 继承 MyBatis-Plus `BaseMapper<TranscodeTask>`。
- 新增 `src/main/java/com/hcx/streamhub/transcode/service/TranscodeTaskService.java`：
  - 提供 `createWaitingTask(Long videoId)`。
  - 创建 `WAITING` 状态任务，`retryCount` 初始化为 `0`。
- 修改 `src/main/java/com/hcx/streamhub/upload/service/VideoUploadService.java`：
  - 上传原始文件到 MinIO 后，创建 `videos` 记录。
  - 随后调用 `transcodeTaskService.createWaitingTask(video.getId())` 创建转码任务。
  - 方法加上 `@Transactional`，保证 `videos` 记录和 `transcode_tasks` 记录在数据库侧同成功或同失败。
- 修改 `scripts/stop-local-app.ps1`：
  - 增加按 8080 端口停止本地验证进程的兜底逻辑。

### 代码链路讲解

- 上传入口仍然是 `VideoController#upload(...)`。
- `VideoUploadService#upload(...)` 现在的业务链路是：
  - `minioStorageService.uploadOriginalVideo(...)`：上传原始视频到 MinIO。
  - `videoService.createWaitingVideo(...)`：写入视频元数据，状态为 `WAITING`。
  - `transcodeTaskService.createWaitingTask(video.getId())`：写入转码任务，状态为 `WAITING`。
- `TranscodeTaskService#createWaitingTask(...)` 只负责创建任务，不做 FFmpeg、不发 MQ。
  - 这是为了先把任务模型稳定下来。
  - 后续 RabbitMQ 只需要围绕这个任务 ID 或 video ID 做异步消费。

### 面试讲解点

- 为什么上传后要创建转码任务：
  - 上传接口应该快速返回，不能直接执行耗时的 FFmpeg。
  - 转码任务表能记录任务状态、失败原因、重试次数和处理时间。
  - 即使 RabbitMQ 或消费者暂时不可用，数据库里也能保留待处理任务。
- 为什么 `VideoUploadService#upload(...)` 加 `@Transactional`：
  - `videos` 和 `transcode_tasks` 都是 MySQL 数据。
  - 如果创建视频成功但创建任务失败，会出现视频永远没人转码的问题。
  - 加事务后，数据库内这两步能保持一致。
- 为什么 MinIO 上传不在同一个事务里：
  - MinIO 是外部对象存储，不参与 MySQL 本地事务。
  - 当前基础版先保证主流程可用。
  - 后续可以做失败补偿：如果数据库事务失败，删除刚上传的 MinIO object；或者做定时清理无主文件。
- 为什么 Consumer 后面不应该写大段业务逻辑：
  - MQ Consumer 应该只做消息解析、幂等检查和调用 Service。
  - 真正的状态流转、FFmpeg 执行、HLS 上传应该放在 `transcode` Service 中，方便测试和复用。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 上传接口返回 `code=0`。
  - 新视频 ID：`3`。
- 已查询 MySQL 验证：
  - `videos.id=3`，`videos.status=WAITING`。
  - `transcode_tasks.id=1`，`transcode_tasks.video_id=3`。
  - `transcode_tasks.status=WAITING`，`retry_count=0`。

### 下一步建议

1. 接 RabbitMQ 基础配置：
   - 定义 exchange、queue、routing key。
   - 定义转码消息 DTO。
2. 上传创建转码任务后发送 MQ 消息。
3. Consumer 收到消息后先查询任务，再进入转码处理流程。

## 2026-06-10 RabbitMQ 转码消息发布

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/transcode/mq/TranscodeRabbitNames.java`：
  - 集中定义 RabbitMQ 名称：
    - exchange：`streamhub.transcode.exchange`
    - queue：`streamhub.transcode.queue`
    - routing key：`transcode.video`
- 新增 `src/main/java/com/hcx/streamhub/transcode/config/TranscodeRabbitConfig.java`：
  - 定义持久化 `DirectExchange`。
  - 定义持久化 `Queue`。
  - 定义 exchange 到 queue 的 binding。
  - 定义 `Jackson2JsonMessageConverter`，让消息以 JSON 形式发送。
  - 配置 `RabbitTemplate` 和 `SimpleRabbitListenerContainerFactory` 使用 JSON 转换器。
- 新增 `src/main/java/com/hcx/streamhub/transcode/dto/TranscodeTaskMessage.java`：
  - MQ 消息体只包含 `taskId` 和 `videoId`。
- 新增 `src/main/java/com/hcx/streamhub/transcode/mq/TranscodeMessagePublisher.java`：
  - 封装 `RabbitTemplate#convertAndSend(...)`。
- 修改 `src/main/java/com/hcx/streamhub/upload/service/VideoUploadService.java`：
  - 创建转码任务后构造 `TranscodeTaskMessage`。
  - 使用 `TransactionSynchronizationManager.registerSynchronization(...)` 在数据库事务提交后发送 MQ 消息。

### 代码链路讲解

- `VideoUploadService#upload(...)` 当前完整链路：
  - 上传原始文件到 MinIO。
  - 创建 `videos` 记录。
  - 创建 `transcode_tasks` 记录。
  - 注册事务提交后的回调。
  - 事务成功提交后调用 `TranscodeMessagePublisher#publish(...)`。
- `TranscodeMessagePublisher#publish(...)` 只负责发消息：
  - exchange 来自 `TranscodeRabbitNames.EXCHANGE`。
  - routing key 来自 `TranscodeRabbitNames.ROUTING_KEY`。
  - 消息体是 `TranscodeTaskMessage`。
- `TranscodeRabbitConfig` 负责基础设施配置：
  - 应用启动时声明 exchange、queue、binding。
  - `RabbitTemplate` 使用 JSON message converter，后续 Consumer 也按同样转换器接收。

### 面试讲解点

- 为什么消息只放 `taskId` 和 `videoId`：
  - MQ 消息应该尽量小，避免把完整视频状态、文件路径等业务快照塞进去。
  - Consumer 拿到消息后查询数据库，以数据库中的任务状态作为准。
  - 这样后续重试、人工修复、任务恢复都更容易。
- 为什么要在事务提交后发送 MQ：
  - 如果在事务提交前发送，Consumer 可能先收到消息，但数据库里任务还没提交，导致查不到任务。
  - 如果事务最后回滚，消息却已经发出，会出现“消息指向不存在任务”的不一致。
  - 当前用 `TransactionSynchronizationManager.afterCommit()`，只有数据库提交成功才发布消息。
- 为什么使用 DirectExchange：
  - 当前只有一种转码消息，routing key 明确，`DirectExchange` 足够简单直接。
  - 后续如果增加不同类型任务，例如封面抽帧、清晰度转码，可以用不同 routing key 继续扩展。
- 为什么 Publisher 单独封装：
  - 上传 Service 不直接依赖 RabbitMQ 名称和发送细节。
  - 后续要加 confirm callback、重试、日志、指标，可以集中改 Publisher。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 上传接口返回 `code=0`。
  - 新视频 ID：`4`。
- 已查询 MySQL 验证：
  - `videos.id=4`，`videos.status=WAITING`。
  - `transcode_tasks.id=2`，`video_id=4`。
  - `transcode_tasks.status=WAITING`，`retry_count=0`。
- 已查询 RabbitMQ 队列：
  - `streamhub.transcode.queue`
  - `messages_ready=1`
  - `messages_unacknowledged=0`

### 下一步建议

1. 实现 RabbitMQ Consumer：
   - 监听 `streamhub.transcode.queue`。
   - 收到 `TranscodeTaskMessage` 后查询任务。
   - 先只打印日志或做任务状态校验。
2. 再实现真正的转码 Service：
   - 标记任务 `PROCESSING`。
   - 调 FFmpeg 生成 HLS。
   - 上传 HLS 文件到 MinIO。
   - 更新视频状态和任务状态。

## 2026-06-10 RabbitMQ 转码 Consumer

### 本次完成

- 修改 `src/main/java/com/hcx/streamhub/transcode/service/TranscodeTaskService.java`：
  - 新增 `getById(Long taskId)`。
  - 查不到任务时抛出 `BusinessException(ErrorCode.NOT_FOUND, "transcode task not found")`。
- 新增 `src/main/java/com/hcx/streamhub/transcode/mq/TranscodeTaskConsumer.java`：
  - 使用 `@RabbitListener(queues = TranscodeRabbitNames.QUEUE)` 监听转码队列。
  - 接收 `TranscodeTaskMessage`。
  - 查询 `transcode_tasks` 任务。
  - 校验消息中的 `videoId` 和任务表中的 `videoId` 是否一致。
  - 校验任务状态是否为 `WAITING`。
  - 当前只记录日志，不执行 FFmpeg。

### 代码链路讲解

- `TranscodeTaskConsumer#consume(...)` 是 RabbitMQ 消费入口。
- Consumer 当前只做三件轻量工作：
  - 根据 `taskId` 查数据库任务。
  - 做基本一致性校验。
  - 把合法的 `WAITING` 任务交给后续处理入口，当前阶段先用日志占位。
- 这里没有直接写 FFmpeg 调用，是为了遵守项目规则：
  - Consumer 不写大段业务逻辑。
  - 后续应新增 `TranscodeService` 或 `VideoTranscodeService`，由 Consumer 调用它。

### 当前完整上传到消费流程

1. 用户登录拿到 JWT。
2. 用户调用 `POST /api/videos/upload` 上传原始视频。
3. `VideoController` 接收 multipart 参数。
4. `VideoUploadService` 编排上传流程。
5. `MinioStorageService` 把原始文件上传到 MinIO。
6. `VideoService` 创建 `videos` 记录，状态为 `WAITING`。
7. `TranscodeTaskService` 创建 `transcode_tasks` 记录，状态为 `WAITING`。
8. MySQL 事务提交成功后，`TranscodeMessagePublisher` 发送 MQ 消息。
9. `TranscodeTaskConsumer` 消费消息，查询并校验任务。
10. 下一步才是真正执行 FFmpeg 转 HLS。

### 面试讲解点

- 为什么 Consumer 先查数据库：
  - MQ 消息只作为“通知”，数据库才是任务状态的事实来源。
  - 这样可以避免消息体过大，也方便任务重试、人工修复和故障恢复。
- 为什么 Consumer 当前不直接转码：
  - Consumer 直接写 FFmpeg、MinIO、状态更新会变得很重。
  - 更好的结构是 Consumer 只接消息，然后调用独立的转码 Service。
- 为什么要校验 `videoId`：
  - 防止消息和任务记录不一致。
  - 后续做重试或手动补发消息时，这个校验能减少错误任务被处理的风险。
- 当前任务仍保持 `WAITING`：
  - 因为还没有真正开始 FFmpeg。
  - 下一步实现转码 Service 时，再把任务状态改成 `PROCESSING`，成功后改 `SUCCESS`，失败后改 `FAILED`。

### 实际场景中的视频存放位置

- 生产环境中，视频通常不放在应用服务器本地磁盘。
- 常见做法是放在对象存储：
  - 自建 MinIO 集群。
  - 云厂商对象存储，例如 S3、OSS、COS。
- 当前项目里，上传的视频放在 MinIO。
  - 因为现在 MinIO 通过 Docker Compose 跑在你的本机，所以当前开发环境里，视频实际存在本机 Docker volume `minio_data` 中。
  - MySQL 只保存 MinIO object key，例如 `original/2/20260610/xxx.mp4`。
- 用户看视频不需要先下载完整文件。
  - 后续 FFmpeg 会把视频转成 HLS：`m3u8` 索引文件加很多 `ts` 切片。
  - 前端播放器加载 `m3u8`，边播边请求小切片，这就是在线播放。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
  - 测试启动时消费了队列中上一阶段留下的消息：`taskId=2, videoId=4`。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 上传接口返回 `code=0`。
  - 新视频 ID：`5`。
- 已查询 MySQL 验证：
  - `videos.id=5`，`videos.status=WAITING`。
  - `transcode_tasks.id=3`，`video_id=5`。
  - `transcode_tasks.status=WAITING`，`retry_count=0`。
- 已查询 RabbitMQ 队列：
  - `streamhub.transcode.queue`
  - `messages_ready=0`
  - `messages_unacknowledged=0`
- 已查看应用日志：
  - `Received waiting transcode task, taskId=3, videoId=5`。

### 下一步建议

1. 新增真正的转码业务 Service。
2. Service 中完成任务状态流转：
   - `WAITING -> PROCESSING`
   - 成功：`PROCESSING -> SUCCESS`
   - 失败：`PROCESSING -> FAILED`
3. 接入 FFmpeg：
   - 下载 MinIO 原始文件到本地临时目录。
   - 生成 HLS 文件。
   - 上传 HLS 到 MinIO。
   - 更新 `videos.hls_master_object_key` 和视频状态。

## 2026-06-10 FFmpeg 转码 Service 初版

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/transcode/config/TranscodeProperties.java`：
  - `streamhub.transcode.ffmpeg-path`：FFmpeg 可执行文件路径，默认 `ffmpeg`。
  - `streamhub.transcode.work-dir`：转码临时目录，默认 `target/transcode`。
- 修改 `src/main/resources/application-dev.yml`：
  - 增加 `streamhub.transcode.ffmpeg-path`。
  - 增加 `streamhub.transcode.work-dir`。
- 修改 `src/main/java/com/hcx/streamhub/upload/service/MinioStorageService.java`：
  - 新增 `downloadToFile(...)`：从 MinIO 下载原始视频到本地临时文件。
  - 新增 `uploadHlsDirectory(...)`：上传 HLS 目录中的 `m3u8` 和 `ts` 文件到 MinIO。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - 新增 `markTranscoding(...)`。
  - 新增 `markPublished(...)`。
  - 新增 `markFailed(...)`。
- 修改 `src/main/java/com/hcx/streamhub/transcode/service/TranscodeTaskService.java`：
  - 新增 `markProcessing(...)`。
  - 新增 `markSuccess(...)`。
  - 新增 `markFailed(...)`。
- 新增 `src/main/java/com/hcx/streamhub/transcode/service/VideoTranscodeService.java`：
  - 查询转码任务。
  - 标记任务 `PROCESSING`。
  - 标记视频 `TRANSCODING`。
  - 从 MinIO 下载原始视频。
  - 调 FFmpeg 生成 HLS。
  - 上传 HLS 到 MinIO。
  - 成功时标记视频 `PUBLISHED`，任务 `SUCCESS`。
  - 失败时标记视频 `FAILED`，任务 `FAILED`。
- 修改 `src/main/java/com/hcx/streamhub/transcode/mq/TranscodeTaskConsumer.java`：
  - Consumer 校验消息后调用 `VideoTranscodeService#transcode(...)`。

### 代码链路讲解

- `TranscodeTaskConsumer#consume(...)` 仍然只负责 MQ 消费和轻量校验。
- 真正业务逻辑下沉到 `VideoTranscodeService#transcode(...)`：
  - 这是为了避免 Consumer 变成“大业务类”。
  - 后续测试转码逻辑时，也可以直接测试 Service，不必依赖 MQ。
- `VideoTranscodeService#runFfmpeg(...)` 使用 `ProcessBuilder` 调本机 FFmpeg。
  - 输入文件来自 MinIO 下载后的临时文件。
  - 输出目录里生成 `master.m3u8` 和 `segment-xxx.ts`。
- `MinioStorageService#uploadHlsDirectory(...)` 会把 HLS 文件上传到：
  - `hls/{videoId}/{uuid}/master.m3u8`
  - `hls/{videoId}/{uuid}/segment-xxx.ts`

### 当前状态流转

- 上传成功：
  - `videos.status=WAITING`
  - `transcode_tasks.status=WAITING`
- Consumer 开始处理：
  - `videos.status=TRANSCODING`
  - `transcode_tasks.status=PROCESSING`
- 转码成功：
  - `videos.status=PUBLISHED`
  - `videos.hls_master_object_key=hls/{videoId}/{uuid}/master.m3u8`
  - `transcode_tasks.status=SUCCESS`
- 转码失败：
  - `videos.status=FAILED`
  - `transcode_tasks.status=FAILED`
  - `transcode_tasks.error_message` 记录失败原因。

### 面试讲解点

- 为什么转码要下载到本地临时目录：
  - FFmpeg 处理本地文件最直接，也方便生成多个 HLS 输出文件。
  - 转码完成后再把 HLS 结果上传回对象存储。
  - 临时目录处理完要清理，避免占满应用服务器磁盘。
- 为什么对象存储也占空间：
  - 对象存储是真实保存文件的地方，当然占磁盘或云存储容量。
  - 它只是比应用服务器本地磁盘更适合存大文件：可扩展、可备份、可做访问控制和 CDN 分发。
  - 当前开发环境中，MinIO 跑在 Docker 里，所以视频占用你本机 Docker volume 的空间。
- 用户是否需要下载完整视频才能看：
  - 不需要。
  - HLS 会把视频切成多个小片段，播放器边请求边播放。
  - 用户浏览器实际会下载正在播放附近的小切片，而不是一次性下载完整视频。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 当前机器未安装 FFmpeg：
  - 执行 `ffmpeg -version` 返回命令不存在。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 上传接口返回 `code=0`。
  - 新视频 ID：`6`。
- 已查询 MySQL 验证失败链路：
  - `videos.id=6`
  - `videos.status=FAILED`
  - `transcode_tasks.id=4`
  - `transcode_tasks.status=FAILED`
  - `error_message=Cannot run program "ffmpeg"...`
- 已查询 RabbitMQ 队列：
  - `messages_ready=0`
  - `messages_unacknowledged=0`

### 下一步建议

1. 安装 FFmpeg 或配置 `streamhub.transcode.ffmpeg-path` 为实际路径。
2. 准备一个真实可转码的视频文件重新验证成功链路。
3. 补播放相关接口：
   - 根据 `videoId` 查询 `hls_master_object_key`。
   - 返回可播放地址或后端代理播放地址。

## 2026-06-10 FFmpeg 安装与成功转码验证

### 本次完成

- 已通过 `winget` 安装 FFmpeg：
  - 包：`Gyan.FFmpeg`
  - 版本：`8.1.1`
- 已确认 FFmpeg 可执行文件存在并可运行：
  - `C:/Users/Probscray/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.1.1-full_build/bin/ffmpeg.exe`
- 修改 `src/main/resources/application-dev.yml`：
  - 将 `streamhub.transcode.ffmpeg-path` 配置为 FFmpeg 绝对路径。
  - 这样应用不依赖当前终端 PATH 是否刷新。
- 修改 `scripts/verify-upload.ps1`：
  - 如果本机存在 FFmpeg，则生成一个真实 2 秒 MP4 测试视频。
  - 不再用文本文件伪装 `.mp4`。

### 配置说明

当前 dev 环境配置：

```yaml
streamhub:
  transcode:
    ffmpeg-path: C:/Users/Probscray/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.1.1-full_build/bin/ffmpeg.exe
    work-dir: target/transcode
```

- `ffmpeg-path`：Java `ProcessBuilder` 实际调用的 FFmpeg 程序。
- `work-dir`：转码时下载原视频和生成 HLS 文件的临时目录。

### 本次成功链路验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 脚本使用 FFmpeg 生成真实测试 MP4。
  - 上传接口返回 `code=0`。
  - 新视频 ID：`7`。
  - 上传文件大小：`29977` 字节。
- 已查询 MySQL：
  - `videos.id=7`
  - `videos.status=PUBLISHED`
  - `videos.hls_master_object_key=hls/7/a2a7f4ee-6b70-48f6-9472-1f97e9d09812/master.m3u8`
  - `transcode_tasks.id=5`
  - `transcode_tasks.status=SUCCESS`
  - `transcode_tasks.error_message=NULL`
- 已查询 RabbitMQ：
  - `streamhub.transcode.queue`
  - `messages_ready=0`
  - `messages_unacknowledged=0`
- 应用日志确认：
  - `Received waiting transcode task, taskId=5, videoId=7`
  - `Transcode task completed, taskId=5, videoId=7`

### 面试讲解点

- FFmpeg 是视频处理工具，不是 Java 库。
  - 项目通过 Java `ProcessBuilder` 调用本机 FFmpeg 进程。
  - FFmpeg 负责把原始 MP4 转成 HLS 文件。
- 为什么配置绝对路径：
  - Windows 安装后 PATH 可能要重启终端才生效。
  - 绝对路径更稳定，服务启动时不会受 shell 环境影响。
- 当前已经跑通核心链路：
  - 上传原视频。
  - 异步消费任务。
  - FFmpeg 转 HLS。
  - HLS 上传 MinIO。
  - 视频状态变成 `PUBLISHED`。

### 下一步建议

1. 补播放接口：
   - 根据 `videoId` 查询视频。
   - 如果状态不是 `PUBLISHED`，返回不可播放。
   - 如果已发布，返回 HLS master object key 或临时访问 URL。
2. 再考虑清理和健壮性：
   - 删除失败任务残留的临时文件。
   - 原视频和 HLS 文件生命周期管理。
   - 失败重试。

## 2026-06-10 HLS 播放接口

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/video/dto/VideoPlayResponse.java`：
  - 返回 `videoId`、`status`、`hlsMasterUrl`。
- 新增 `src/main/java/com/hcx/streamhub/upload/dto/ObjectStream.java`：
  - 封装从 MinIO 打开的对象流和 content type。
- 修改 `src/main/java/com/hcx/streamhub/upload/service/MinioStorageService.java`：
  - 新增 `openObjectStream(String objectKey)`。
  - 后端可以从 MinIO 打开 `m3u8` 或 `ts` 对象并流式返回给浏览器。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - 新增 `getPlayableVideo(Long videoId)`。
  - 只有 `PUBLISHED` 且存在 `hlsMasterObjectKey` 的视频才能播放。
- 新增 `src/main/java/com/hcx/streamhub/video/service/VideoPlaybackService.java`：
  - `getPlayInfo(...)` 返回播放入口 URL。
  - `openHlsObject(...)` 根据视频的 HLS 前缀读取 `master.m3u8` 或 `.ts` 切片。
  - 校验 HLS 文件名，禁止路径穿越。
- 修改 `src/main/java/com/hcx/streamhub/video/controller/VideoController.java`：
  - 新增 `GET /api/videos/{videoId}/play`。
  - 新增 `GET /api/videos/{videoId}/hls/{filename}`。
- 修改 `src/main/java/com/hcx/streamhub/common/ErrorCode.java`：
  - 新增 `VIDEO_NOT_PLAYABLE`。
- 新增 `scripts/verify-playback.ps1`：
  - 登录测试用户。
  - 请求播放信息。
  - 请求 `master.m3u8`。

### 代码链路讲解

- `GET /api/videos/{videoId}/play`
  - 调 `VideoPlaybackService#getPlayInfo(...)`。
  - 内部先通过 `VideoService#getPlayableVideo(...)` 判断视频是否已发布。
  - 返回 `/api/videos/{videoId}/hls/master.m3u8`。
- `GET /api/videos/{videoId}/hls/{filename}`
  - 调 `VideoPlaybackService#openHlsObject(...)`。
  - 根据 `hls_master_object_key` 得到 HLS 目录前缀。
  - 拼出 MinIO object key。
  - 调 `MinioStorageService#openObjectStream(...)` 读取对象。
  - Controller 用 `InputStreamResource` 把对象流返回给客户端。
- 为什么使用后端代理：
  - 当前 MinIO bucket 不需要公开。
  - 浏览器只访问 Spring Boot 接口。
  - 后端负责鉴权和从 MinIO 读取文件。

### 面试讲解点

- 为什么 `m3u8` 可以返回相对路径：
  - FFmpeg 生成的 `master.m3u8` 中通常引用 `segment-000.ts` 这类相对文件名。
  - 浏览器从 `/api/videos/7/hls/master.m3u8` 加载时，会自动请求 `/api/videos/7/hls/segment-000.ts`。
- 为什么要校验文件名：
  - `filename` 来自 URL，不能直接拼 MinIO key。
  - 代码使用 `StringUtils.cleanPath(...)` 并禁止 `/`、`\`，避免路径穿越。
- 为什么未发布视频不能播放：
  - 上传成功只代表原始文件存在。
  - 必须转码成功并生成 `hls_master_object_key` 后，播放器才有可加载的 HLS 入口。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-playback.ps1`。
  - `GET /api/videos/7/play` 返回：
    - `code=0`
    - `status=PUBLISHED`
    - `hlsMasterUrl=/api/videos/7/hls/master.m3u8`
  - `GET /api/videos/7/hls/master.m3u8` 返回：
    - HTTP `200`
    - content type：`application/vnd.apple.mpegurl`
    - 内容以 `#EXTM3U` 开头。

### 下一步建议

1. 给视频查询接口补详情接口。
2. 给前端或测试页面接入 HLS 播放器。
3. 增加失败重试能力和转码任务重试接口。

## 2026-06-10 视频详情接口

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/video/dto/VideoDetailResponse.java`：
  - 返回详情页需要的视频元数据。
  - 增加 `playable` 字段，表示当前视频是否可播放。
  - 增加 `playUrl` 字段，可播放时返回 `/api/videos/{videoId}/play`。
  - 不暴露内部 MinIO object key。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - 新增 `toDetailResponse(Video video)`。
- 修改 `src/main/java/com/hcx/streamhub/video/controller/VideoController.java`：
  - 新增 `GET /api/videos/{videoId}`。
  - Controller 仍然只负责接收路径参数和返回统一 `Result<T>`。
- 新增 `scripts/verify-video-detail.ps1`：
  - 登录测试用户。
  - 请求 `GET /api/videos/7`。

### 代码思想

- 为什么不用已有 `VideoResponse`：
  - `VideoResponse` 是基础响应，当前包含 `hlsMasterObjectKey`。
  - 详情接口更接近前端页面需要，不应该把内部对象存储 key 直接暴露给前端。
  - 所以新增 `VideoDetailResponse`，把内部字段转换成更稳定的业务字段：`playable` 和 `playUrl`。
- `playable` 如何计算：
  - 视频状态必须是 `PUBLISHED`。
  - 并且 `hlsMasterObjectKey` 不能为空。
  - 代码位置：`VideoDetailResponse#from(Video video)`。
- `playUrl` 为什么指向 `/play`：
  - 详情页只需要知道“去哪里获取播放信息”。
  - 真正的 HLS master URL 仍由播放接口返回，职责更清楚。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-video-detail.ps1`。
  - `GET /api/videos/7` 返回 `code=0`。
  - `status=PUBLISHED`。
  - `playable=true`。
  - `playUrl=/api/videos/7/play`。

### 下一步建议

1. 实现视频列表接口：
   - `GET /api/videos`：公开视频列表，只返回 `PUBLISHED`。
   - `GET /api/videos/my`：当前用户上传的视频列表。
2. 再补转码重试能力。

## 2026-06-10 视频列表接口

### 本次完成

- 新增 `src/main/java/com/hcx/streamhub/common/PageResponse.java`：
  - 统一分页响应结构。
  - 字段包括 `records`、`total`、`pageNo`、`pageSize`、`pages`。
  - 提供 `from(IPage<S> page, Function<S, T> mapper)`，方便把实体分页转换成 DTO 分页。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - 新增 `listPublished(PageRequest request)`。
  - 新增 `listByUser(Long userId, PageRequest request)`。
  - 使用 MyBatis-Plus `Page` 和 `LambdaQueryWrapper`。
- 修改 `src/main/java/com/hcx/streamhub/video/controller/VideoController.java`：
  - 新增 `GET /api/videos`：公开视频列表，只返回 `PUBLISHED` 视频。
  - 新增 `GET /api/videos/my`：当前登录用户上传的视频列表。
- 新增 `scripts/verify-video-list.ps1`：
  - 登录测试用户。
  - 验证公开视频列表。
  - 验证我的视频列表。

### 代码思想

- 为什么需要 `PageResponse<T>`：
  - 直接返回 MyBatis-Plus 的 `Page` 会把框架内部字段暴露给前端。
  - `PageResponse<T>` 是项目自己的 API 契约，字段更稳定。
- 为什么公开视频列表只查 `PUBLISHED`：
  - `WAITING`、`TRANSCODING`、`FAILED` 都不是可播放内容。
  - 首页或公开视频流只应该展示用户可以点开播放的视频。
- 为什么我的视频列表返回全部状态：
  - 上传者需要看到自己的视频处理进度。
  - 例如 `WAITING`、`TRANSCODING`、`FAILED` 都应该在“我的视频”里可见。
- 为什么按 `createdAt` 倒序：
  - 视频平台常见默认排序是最新发布/最新上传在前。
  - 后续热门排序可以单独接 Redis 热度榜。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-video-list.ps1`。
  - `GET /api/videos?pageNo=1&pageSize=5` 返回 `code=0`。
  - 公开视频列表只包含 `PUBLISHED` 视频。
  - 当前测试数据下公开视频总数为 `1`。
  - `GET /api/videos/my?pageNo=1&pageSize=5` 返回 `code=0`。
  - 我的视频列表返回当前用户上传的视频，包含 `PUBLISHED`、`FAILED`、`WAITING` 等状态。
  - 当前测试数据下我的视频总数为 `7`，第一页返回 `5` 条。

### 下一步建议

1. 做转码任务可靠性：
   - 原子抢占 `WAITING -> PROCESSING`。
   - 失败重试。
   - 避免重复消费导致重复转码。
2. 或者继续补业务功能：
   - 点赞。
   - 收藏。
   - 评论。
## 2026-06-10 转码任务原子抢占和消费幂等

### 本次完成

- 修改 `src/main/java/com/hcx/streamhub/transcode/service/TranscodeTaskService.java`：
  - 新增 `tryStartTask(Long taskId)`。
  - 使用 MyBatis-Plus `LambdaUpdateWrapper` 做条件更新。
  - 只有 `WAITING` 状态的任务可以被更新为 `PROCESSING`。
  - 更新成功返回 `true`，更新失败返回 `false`。
  - `markSuccess(...)` 和 `markFailed(...)` 补充写入 `finishedAt`。
- 修改 `src/main/java/com/hcx/streamhub/transcode/mq/TranscodeTaskConsumer.java`：
  - Consumer 收到消息后先校验任务和视频 ID。
  - 执行 FFmpeg 前必须先调用 `tryStartTask(...)`。
  - 抢占失败后重新查询任务状态：
    - `SUCCESS`：说明任务已经完成，重复消息直接跳过。
    - `PROCESSING`：说明已有 Consumer 正在处理，当前消息直接跳过。
    - `FAILED`：当前阶段暂不重试，直接跳过。
  - 增加必要日志，便于观察任务被抢占、重复消费被跳过、异常消息被忽略。
- 修改 `src/main/java/com/hcx/streamhub/transcode/service/VideoTranscodeService.java`：
  - 转码入口只处理 `PROCESSING` 状态的任务。
  - 不再在转码服务内部把任务从 `WAITING` 改成 `PROCESSING`，状态流转入口统一放到 Consumer 的原子抢占阶段。

### 解决的问题

- RabbitMQ 至少一次投递模型下，同一条转码消息可能被重复投递。
- 多个 Consumer 并发消费同一个 `taskId` 时，如果只是先查询再更新，可能出现多个 Consumer 都认为任务可以处理，导致重复执行 FFmpeg。
- 本次改造把“判断任务是否待处理”和“把任务改为处理中”合并成一条数据库条件更新：

```java
UPDATE transcode_tasks
SET status = 'PROCESSING', started_at = NOW()
WHERE id = ? AND status = 'WAITING'
```

这条 SQL 天然具备原子性。同一时刻只有一个 Consumer 能更新成功，其他 Consumer 的更新行数为 0，只能走幂等跳过逻辑。

### 代码思想

- `tryStartTask(...)` 是抢锁动作，不是普通状态修改。
- 抢占成功代表当前 Consumer 获得这个任务的处理权，可以继续执行 FFmpeg。
- 抢占失败不代表系统错误，而是说明任务状态已经被别人改变，所以 Consumer 必须再次读取数据库状态并按状态处理。
- 幂等的核心不是“消息不重复”，而是“重复消息到来时不会造成重复业务结果”。这里的业务结果就是不会重复转码同一个 `transcode_task`。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 新视频 ID：`8`。
  - `videos.status=PUBLISHED`。
  - `transcode_tasks.id=6`。
  - `transcode_tasks.status=SUCCESS`。
  - `streamhub.transcode.queue` 中 `messages_ready=0`、`messages_unacknowledged=0`。
  - 应用日志确认任务先被抢占：`Claimed transcode task, taskId=6, videoId=8`。

### 后续建议

1. 在当前原子抢占基础上继续做失败重试：
   - 设计 `retry_count` 和 `max_retry_count`。
   - `FAILED` 任务在未超过最大次数时重新投递或重新置为 `WAITING`。
2. 加入 publisher confirm：
   - 确认上传事务提交后，消息确实到达 RabbitMQ broker。
3. 增加失败文件和 MinIO 对象清理：
   - 清理转码失败留下的 HLS 中间对象。
   - 删除视频时同步清理 original、hls、cover。
## 2026-06-10 转码任务失败重试机制

### 本次完成

- 修改 `docs/sql/schema.sql`：
  - `transcode_tasks.retry_count`：记录当前任务已经失败的次数，已有字段继续复用。
  - 新增 `transcode_tasks.max_retry_count`：单个任务最大失败次数，默认值为 `3`。
  - 新增 `transcode_tasks.last_error_message`：记录最近一次失败原因。
- 修改 `src/main/java/com/hcx/streamhub/transcode/entity/TranscodeTask.java`：
  - 新增 `maxRetryCount`。
  - 新增 `lastErrorMessage`。
  - 不再使用旧的 `errorMessage` 实体字段。
- 修改 `src/main/java/com/hcx/streamhub/transcode/service/TranscodeTaskService.java`：
  - 创建任务时初始化 `retryCount=0`、`maxRetryCount=3`。
  - 新增 `markFailedOrWaitingForRetry(...)`。
  - FFmpeg 失败后统一在 Service 中处理失败次数、状态流转和错误信息。
- 修改 `src/main/java/com/hcx/streamhub/transcode/service/VideoTranscodeService.java`：
  - 成功链路保持不变：上传 HLS、发布视频、标记任务成功。
  - 失败链路改为：
    - `retry_count + 1`。
    - 写入 `last_error_message`。
    - 如果 `retry_count < max_retry_count`，任务改回 `WAITING`。
    - 数据库更新成功后重新发送 MQ 消息。
    - 如果 `retry_count >= max_retry_count`，任务改为 `FAILED`，视频改为 `FAILED`。

### 解决的问题

- 之前 FFmpeg 失败后任务会直接进入 `FAILED`，无法自动恢复临时故障。
- 现在可以处理短暂故障，例如：
  - FFmpeg 进程偶发失败。
  - MinIO 下载或上传短暂异常。
  - 本地临时目录或 IO 短暂异常。
- 通过 `max_retry_count=3` 限制最大失败次数，避免无限重新投递 MQ。

### 代码思想

- 重试不是 RabbitMQ 自己无限重投，而是业务自己控制次数。
- 每次失败先更新数据库，再决定是否重新发 MQ。
- 数据库记录是重试次数的事实来源，所以服务重启后也不会丢失已经失败过几次。
- `retry_count < max_retry_count` 才重新进入 `WAITING`，这样下一条 MQ 消息仍然会经过 `tryStartTask(...)` 的原子抢占。
- 达到最大次数后任务进入 `FAILED`，Consumer 后续收到重复消息也会按幂等逻辑跳过。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已对本地 MySQL 执行表结构同步：
  - 新增 `max_retry_count`。
  - 新增 `last_error_message`。
  - 历史 `error_message` 内容已同步到 `last_error_message`。
- 已用错误 FFmpeg 路径验证失败重试：
  - 视频 ID：`9`。
  - 任务 ID：`7`。
  - 第 1 次失败：`retry_count=1`，状态改回 `WAITING`，重新发送 MQ。
  - 第 2 次失败：`retry_count=2`，状态改回 `WAITING`，重新发送 MQ。
  - 第 3 次失败：`retry_count=3`，达到 `max_retry_count=3`，任务进入 `FAILED`。
  - 最终 `videos.status=FAILED`、`transcode_tasks.status=FAILED`。
  - RabbitMQ 队列无堆积。
- 已用正常 FFmpeg 配置验证成功链路未受影响：
  - 视频 ID：`10`。
  - 任务 ID：`8`。
  - `videos.status=PUBLISHED`。
  - `transcode_tasks.status=SUCCESS`。
  - `retry_count=0`、`max_retry_count=3`、`last_error_message=NULL`。

### 面试讲解点

- 为什么不用 RabbitMQ 无限重试：
  - MQ 只负责投递，业务失败次数应该由业务表记录。
  - 如果只靠 MQ 重投，服务重启、重复投递和最大次数控制都会变得不清晰。
- 为什么重新发 MQ 要在数据库更新后：
  - 如果先发消息，Consumer 可能马上消费，但数据库任务状态还没变回 `WAITING`。
  - 这会导致 `tryStartTask(...)` 抢占失败，消息被跳过。
- 为什么失败后不是立刻把视频标记为 `FAILED`：
  - 只要任务还会重试，视频还有机会成功发布。
  - 只有达到最大重试次数后，才把视频最终标记为失败。
## 2026-06-10 转码完成后提取视频元数据和封面

### 本次完成

- 修改 `docs/sql/schema.sql`：
  - `videos.duration_seconds` 继续复用为视频时长字段。
  - 新增 `videos.width`。
  - 新增 `videos.height`。
  - 新增 `videos.cover_object_key`，用于保存 MinIO 中的封面对象 key。
- 修改 `src/main/resources/application-dev.yml`：
  - 新增 `streamhub.transcode.ffprobe-path`。
  - Windows 开发环境使用 FFmpeg 同目录下的 `ffprobe.exe`。
- 修改 `src/main/java/com/hcx/streamhub/transcode/config/TranscodeProperties.java`：
  - 新增 `ffprobePath` 配置项。
- 修改 `src/main/java/com/hcx/streamhub/transcode/service/VideoTranscodeService.java`：
  - 下载原视频后先调用 `ffprobe` 提取：
    - `duration`
    - `width`
    - `height`
  - 使用 FFmpeg 截取 `cover.jpg`。
  - HLS 转码成功后上传 HLS 文件和封面文件。
  - 发布视频时写入 `duration_seconds`、`width`、`height`、`cover_object_key`。
- 修改 `src/main/java/com/hcx/streamhub/upload/service/MinioStorageService.java`：
  - 新增 `uploadCover(...)`。
  - 支持 `jpg/jpeg/png` 的响应 content type。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoPlaybackService.java` 和 `VideoController.java`：
  - 新增 `GET /api/videos/{videoId}/cover`。
  - 通过后端代理读取 MinIO 封面对象。
- 修改 `src/main/java/com/hcx/streamhub/video/dto/VideoDetailResponse.java`：
  - 返回 `duration`、`width`、`height`、`coverUrl`。
  - `coverUrl` 是 `/api/videos/{videoId}/cover`，不是 MinIO object key。
- 修改 `src/main/java/com/hcx/streamhub/video/dto/VideoResponse.java`：
  - 上传响应不再返回 `hlsMasterObjectKey`。
  - 避免接口暴露 MinIO 内部 object key。
- 新增 `scripts/verify-video-metadata.ps1`：
  - 验证详情接口、列表接口和封面代理接口。

### 代码思想

- 元数据必须以后端实际解析为准，不能信任用户上传时提供的文件名或前端参数。
- `ffprobe` 只负责读取媒体信息，不做转码：
  - 输出视频流宽高。
  - 输出容器时长。
- FFmpeg 仍负责媒体处理：
  - 一路生成 HLS。
  - 另一路截取封面图。
- 数据库存储内部 object key：
  - `cover_object_key=cover/{videoId}/{uuid}.jpg`
- API 返回外部访问 URL：
  - `coverUrl=/api/videos/{videoId}/cover`
- 这样前端不需要知道 MinIO bucket、endpoint 和 object key，后续鉴权、限流、私有 bucket 都由后端统一控制。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已同步本地 MySQL 表结构：
  - 新增 `cover_object_key`。
  - 新增 `width`。
  - 新增 `height`。
- 已启动本地应用并执行 `scripts/verify-upload.ps1`。
  - 新视频 ID：`11`。
  - `videos.status=PUBLISHED`。
  - `duration_seconds=2`。
  - `width=320`。
  - `height=180`。
  - `cover_object_key=cover/11/...jpg`。
  - `transcode_tasks.status=SUCCESS`。
- 已执行 `scripts/verify-video-metadata.ps1 -VideoId 11`。
  - 详情接口返回：
    - `coverUrl=/api/videos/11/cover`
    - `duration=2`
    - `width=320`
    - `height=180`
  - 列表接口返回：
    - `coverUrl=/api/videos/11/cover`
    - `duration=2`
  - 封面接口返回：
    - HTTP `200`
    - `Content-Type=image/jpeg`
    - 内容长度 `7262` 字节。
- 已检查 RabbitMQ：
  - `messages_ready=0`
  - `messages_unacknowledged=0`

### 面试讲解点

- 为什么用 `ffprobe` 提取元数据：
  - 它是 FFmpeg 套件里专门读取媒体信息的工具，比手动解析文件格式可靠。
- 为什么封面不直接返回 MinIO 地址：
  - 当前 bucket 可以保持私有。
  - 前端只访问后端接口。
  - 后端可以统一做登录校验、权限控制和防盗链。
- 为什么元数据在转码完成时写入：
  - 只有成功完成媒体处理后，视频才具备可播放内容和封面。
  - 元数据、HLS、封面一起落库，可以保证详情和列表看到的是完整发布态数据。
## 2026-06-10 视频互动基础模块

### 本次完成

- 修改 `docs/sql/schema.sql`：
  - `videos` 表新增：
    - `like_count`
    - `collect_count`
    - `comment_count`
  - 新增 `video_likes` 表：
    - 保存用户对视频的点赞记录。
    - 使用唯一索引 `uk_video_likes_video_user(video_id, user_id)` 防止重复点赞。
  - 新增 `video_collects` 表：
    - 保存用户对视频的收藏记录。
    - 使用唯一索引 `uk_video_collects_video_user(video_id, user_id)` 防止重复收藏。
  - 新增 `video_comments` 表：
    - 保存视频评论。
    - 使用 `(video_id, created_at)` 索引支持按视频分页查询评论。
- 修改 `src/main/java/com/hcx/streamhub/video/entity/Video.java`：
  - 新增 `likeCount`、`collectCount`、`commentCount`。
- 修改 `src/main/java/com/hcx/streamhub/video/dto/VideoDetailResponse.java` 和 `VideoResponse.java`：
  - 返回点赞数、收藏数、评论数。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - 新增 `getPublishedVideo(...)`。
  - 只有 `PUBLISHED` 视频允许互动。
  - 新增计数原子更新方法：
    - `incrementLikeCount(...)`
    - `decrementLikeCount(...)`
    - `incrementCollectCount(...)`
    - `decrementCollectCount(...)`
    - `incrementCommentCount(...)`
- 新增互动模块：
  - `VideoLike`
  - `VideoCollect`
  - `VideoLikeMapper`
  - `VideoCollectMapper`
  - `VideoInteractionService`
  - `VideoInteractionController`
  - `InteractionStatusResponse`
- 新增评论模块：
  - `VideoComment`
  - `VideoCommentMapper`
  - `CreateCommentRequest`
  - `VideoCommentResponse`
  - `VideoCommentService`
  - `VideoCommentController`
- 新增 `scripts/verify-interaction.ps1`：
  - 验证点赞、重复点赞、收藏、重复收藏、发表评论、评论列表、取消点赞、取消收藏。

### 新增接口

- `GET /api/videos/{videoId}/interaction`
  - 查询当前用户是否点赞、是否收藏，以及视频当前互动计数。
- `POST /api/videos/{videoId}/like`
  - 点赞。
- `DELETE /api/videos/{videoId}/like`
  - 取消点赞。
- `POST /api/videos/{videoId}/collect`
  - 收藏。
- `DELETE /api/videos/{videoId}/collect`
  - 取消收藏。
- `GET /api/videos/{videoId}/comments?pageNo=1&pageSize=10`
  - 分页查询评论。
- `POST /api/videos/{videoId}/comments`
  - 发表评论。

### 代码思想

- 点赞和收藏是“用户-视频”的关系表，不直接只存一个计数字段。
- 关系表用于判断当前用户是否已经点赞或收藏。
- `videos.like_count`、`collect_count`、`comment_count` 是冗余计数字段，用于列表和详情快速展示。
- 防重复依赖两层：
  - Service 先查是否存在。
  - 数据库唯一索引兜底，避免并发重复插入。
- 计数更新使用数据库原子表达式：

```sql
like_count = like_count + 1
collect_count = collect_count + 1
comment_count = comment_count + 1
```

- 取消点赞和取消收藏时，只有实际删除了关系行才减少计数，避免重复取消导致计数异常。
- 所有互动前都会调用 `VideoService#getPublishedVideo(...)`，只有 `PUBLISHED` 视频允许互动。
- 当前用户统一通过 `@AuthenticationPrincipal AuthenticatedUser` 获取，未登录请求由 Spring Security 拦截并返回统一 `Result`。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已同步本地 MySQL 表结构：
  - `videos` 新增三个计数字段。
  - 创建 `video_likes`、`video_collects`、`video_comments`。
- 已启动本地应用并执行：

```powershell
scripts/verify-interaction.ps1 -VideoId 11
```

- 验证结果：
  - 第一次点赞后 `likeCount=1`。
  - 重复点赞后 `likeCount` 仍为 `1`。
  - 第一次收藏后 `collectCount=1`。
  - 重复收藏后 `collectCount` 仍为 `1`。
  - 发表评论成功，评论列表 `total=1`。
  - 取消点赞后 `likeCount=0`。
  - 取消收藏后 `collectCount=0`。
- 已查询 MySQL：
  - `videos.id=11`：
    - `like_count=0`
    - `collect_count=0`
    - `comment_count=1`
  - `video_likes` 中该视频点赞记录为 `0`。
  - `video_collects` 中该视频收藏记录为 `0`。
  - `video_comments` 中存在本次发布的评论。

### 面试讲解点

- 为什么点赞收藏需要关系表：
  - 只存计数无法判断某个用户是否已经点过赞。
  - 关系表可以支持“我的点赞”“我的收藏”等后续功能。
- 为什么还要在 `videos` 表保存计数：
  - 列表页和详情页频繁展示计数，如果每次都 `COUNT(*)` 会增加数据库压力。
  - 当前阶段直接同步更新 MySQL，后续播放量和热门榜可以改成 Redis 聚合后异步落库。
- 为什么需要唯一索引：
  - Service 查询只能减少重复请求，不能解决并发插入。
  - 唯一索引是最终一致的防线。
- 为什么只允许 `PUBLISHED` 视频互动：
  - 未发布、转码中、失败的视频不应该进入公开互动体系。
## 2026-06-10 播放量统计和热门榜

### 本次完成

- 修改 `docs/sql/schema.sql`：
  - `videos` 表新增 `play_count` 字段，默认值为 `0`。
- 修改 `src/main/java/com/hcx/streamhub/video/entity/Video.java`：
  - 新增 `playCount`。
- 修改 `src/main/java/com/hcx/streamhub/video/dto/VideoDetailResponse.java` 和 `VideoResponse.java`：
  - 返回 `playCount`。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - 新增 `incrementPlayCount(...)`。
  - 新增热门视频查询辅助方法：
    - `listHotByIds(...)`
    - `listHotFromDatabase(...)`
- 新增 `src/main/java/com/hcx/streamhub/video/service/VideoViewService.java`：
  - `recordPlay(videoId)`：记录播放事件。
  - `listHot(limit)`：从 Redis ZSet 查询热门视频。
  - Redis key：`streamhub:video:hot`。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoPlaybackService.java`：
  - `GET /api/videos/{videoId}/play` 被访问时增加播放量。
  - 播放量同步写入 MySQL，并同步增加 Redis ZSet 分数。
- 修改 `src/main/java/com/hcx/streamhub/video/controller/VideoController.java`：
  - 新增 `GET /api/videos/hot?limit=10`。
- 新增 `scripts/verify-hot-videos.ps1`：
  - 登录后连续访问播放接口。
  - 验证 `playCount` 增长。
  - 验证热门榜返回对应视频。

### 代码思想

- 播放接口被访问代表一次播放意图，当前基础版直接计入播放量。
- MySQL 的 `videos.play_count` 是最终展示用计数字段。
- Redis ZSet 用于热门榜排序：

```text
key: streamhub:video:hot
member: videoId
score: play count increment
```

- 每次访问播放接口：
  - 校验视频必须可播放。
  - `videos.play_count = play_count + 1`。
  - `ZINCRBY streamhub:video:hot 1 {videoId}`。
- 热门榜读取流程：
  - 先从 Redis ZSet 按分数倒序取 Top N。
  - 再根据 videoId 回 MySQL 查询视频详情。
  - 如果 Redis 暂无数据，则按 MySQL `play_count` 降序兜底。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已同步本地 MySQL 表结构：
  - `videos` 新增 `play_count`。
- 已启动本地应用并执行：

```powershell
scripts/verify-hot-videos.ps1 -VideoId 11
```

- 验证结果：
  - 播放前 `playCount=0`。
  - 连续访问播放接口 3 次。
  - 播放后 `playCount=3`。
  - 热门榜第一条视频 ID 为 `11`。
  - 热门榜第一条 `playCount=3`。
- 已查询 MySQL：
  - `videos.id=11`
  - `status=PUBLISHED`
  - `play_count=3`
- 已查询 Redis：
  - `ZREVRANGE streamhub:video:hot 0 4 WITHSCORES`
  - 返回 `11 -> 3`。

### 面试讲解点

- 为什么热门榜用 Redis ZSet：
  - ZSet 天然支持分数排序，适合 Top N 榜单。
  - 每次播放只需要 `ZINCRBY`，操作简单且性能高。
- 为什么 MySQL 还要保存 `play_count`：
  - 视频详情和列表需要稳定展示播放量。
  - Redis 可以作为排行榜加速层，MySQL 是基础数据源。
- 当前版本为什么直接同步 MySQL：
  - 项目还处于基础功能阶段，直接同步逻辑简单、容易验证。
  - 后续播放量高频增长后，可以升级为 Redis 聚合 + 定时异步落库。
- 为什么播放接口要先校验可播放：
  - 只有 `PUBLISHED` 且存在 HLS 的视频才应该产生有效播放量。
## 2026-06-10 播放量 Redis 增量统计和分页热门榜

### 本次完成

- 修改 `src/main/java/com/hcx/streamhub/StreamhubApplication.java`：
  - 增加 `@EnableScheduling`。
  - 启用 Spring 定时任务。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoViewService.java`：
  - 播放接口访问时不再直接更新 MySQL。
  - 播放量先写 Redis Hash：
    - `streamhub:video:play:delta`
    - field：`videoId`
    - value：待刷回 MySQL 的播放增量。
  - 使用 Redis ZSet 维护热门榜：
    - `streamhub:video:hot`
    - member：`videoId`
    - score：热度分。
  - 新增定时任务 `flushPlayCountToDatabase()`：
    - 每 30 秒读取 Redis 播放量增量。
    - 批量刷回 `videos.play_count`。
    - 刷库成功后扣减 Redis Hash 中对应增量。
- 修改 `src/main/java/com/hcx/streamhub/video/service/VideoService.java`：
  - `incrementPlayCount(videoId, delta)` 支持按增量更新 MySQL。
  - 热门榜查询支持分页结构。
  - 从 Redis 热榜取 videoId 后回 MySQL 查询视频详情。
- 修改 `src/main/java/com/hcx/streamhub/video/controller/VideoController.java`：
  - `GET /api/videos/hot` 返回 `PageResponse<VideoDetailResponse>`。
  - 支持 `pageNo`、`pageSize` 参数。
- 修改互动模块：
  - 点赞、取消点赞后刷新 Redis 热度分。
  - 收藏、取消收藏后刷新 Redis 热度分。
  - 发表评论后刷新 Redis 热度分。
- 修改 `scripts/verify-hot-videos.ps1`：
  - 验证播放量先进入 Redis。
  - 验证热门榜分页返回。
  - 等待定时任务刷库后验证 MySQL 播放量变化。

### 热度分设计

当前基础版热度分公式：

```text
hot_score =
  view_count * 1
  + like_count * 3
  + collect_count * 4
  + comment_count * 2
```

项目数据库字段名使用 `play_count` 表示播放量，对应需求里的 `view_count`。

播放接口访问时：

1. 校验视频可播放。
2. Redis Hash 中 `play delta + 1`。
3. Redis ZSet 中该视频热度分 `+ 1`。
4. 不直接更新 MySQL。

点赞、收藏、评论变化时：

1. 先更新 MySQL 中的互动关系和计数字段。
2. 再按最新的 `play_count + Redis待刷播放增量 + 互动计数权重` 重算 ZSet 分数。

### 为什么这样设计

- 播放量是高频写入，如果每次播放都直接写 MySQL，会给数据库带来高写压力。
- Redis Hash 适合暂存增量，后续定时聚合落库。
- Redis ZSet 适合维护 Top N 热门榜。
- MySQL 仍然保存 `play_count`，用于详情、列表和数据持久化。
- 热门榜返回分页结构，和项目已有列表接口风格一致。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
- 已启动本地应用并执行：

```powershell
scripts/verify-hot-videos.ps1 -VideoId 11
```

- 验证结果：
  - 播放前 MySQL `playCount=9`。
  - 连续访问播放接口 3 次。
  - 播放后立即查询详情，`playCount` 仍为 `9`，说明没有每次直接写 MySQL。
  - 热门榜接口返回分页结构：
    - `pageNo=1`
    - `pageSize=5`
    - `records[0].id=11`
  - 等待定时任务刷库后，详情 `playCount=12`。
- 已查询 MySQL：
  - `videos.id=11`
  - `play_count=12`
  - `like_count=0`
  - `collect_count=0`
  - `comment_count=1`
- 已查询 Redis：
  - `HGETALL streamhub:video:play:delta` 为空，说明播放增量已刷回 MySQL。
  - `ZREVRANGE streamhub:video:hot 0 4 WITHSCORES` 返回：
    - `11`
    - `14`
  - 分数 `14 = play_count 12 + comment_count 1 * 2`。

### 面试讲解点

- 播放量统计是典型高频写场景，适合先写 Redis，再批量落库。
- Redis Hash 保存增量，避免频繁更新 MySQL 同一行。
- Redis ZSet 保存热度分，天然支持排行榜倒序分页。
- 定时刷库需要注意增量扣减：
  - 读取到 delta 后先写 MySQL。
  - 写库成功后用 `HINCRBY -delta` 扣减。
  - 如果刷库期间又有新播放，只会保留新产生的增量，不会误删。
- 当前版本是基础可靠实现，后续可以继续增强：
  - 定时任务分布式锁。
  - Redis 增量持久化保护。
  - 热度分时间衰减。
## 2026-06-10 Netty WebSocket 弹幕基础服务

### 本次完成

- 修改 `pom.xml`：
  - 新增 `io.netty:netty-all` 依赖。
- 修改 `src/main/resources/application-dev.yml`：
  - 新增弹幕 Netty 配置：

```yaml
streamhub:
  danmaku:
    netty-port: 8090
    path: /ws/danmaku
    heartbeat-timeout-seconds: 60
```

- 新增 `src/main/java/com/hcx/streamhub/danmaku/config/DanmakuNettyProperties.java`：
  - 管理 Netty 端口、WebSocket path、心跳超时时间。
- 新增 `src/main/java/com/hcx/streamhub/danmaku/netty/DanmakuNettyServer.java`：
  - 实现 `SmartLifecycle`。
  - Spring Boot 启动时自动启动 Netty WebSocket Server。
  - 监听独立端口 `8090`。
  - Spring Boot 关闭时释放 boss/worker 线程组。
- 新增 `DanmakuChannelInitializer`：
  - 配置 Netty pipeline：
    - `HttpServerCodec`
    - `HttpObjectAggregator`
    - `IdleStateHandler`
    - `DanmakuHandshakeHandler`
    - `WebSocketServerProtocolHandler`
    - `DanmakuFrameHandler`
- 新增 `DanmakuHandshakeHandler`：
  - 支持连接格式：

```text
ws://localhost:8090/ws/danmaku?videoId={videoId}&token={jwt}
```

  - 握手阶段解析 `videoId` 和 `token`。
  - 调用现有 `JwtTokenProvider#parseToken(...)` 校验 JWT。
  - 校验视频必须是 `PUBLISHED`。
  - 校验失败返回 `401` 或 `404` 并关闭连接。
  - 校验成功后把 `videoId`、`userId` 写入 Channel Attribute。
- 新增 `DanmakuRoomManager`：
  - 使用内存结构维护：

```text
videoId -> ChannelGroup
```

  - 支持加入房间、离开房间、同房间广播。
  - 房间为空时自动清理。
- 新增 `DanmakuFrameHandler`：
  - WebSocket 握手完成后加入 `videoId` 对应房间。
  - 支持文本心跳：

```json
{"type":"PING"}
```

  - 返回：

```json
{"type":"PONG"}
```

  - 支持 WebSocket 原生 PingFrame/PongFrame。
  - 用户发送弹幕后，广播给同一 `videoId` 房间内所有连接。
  - 连接断开、异常、心跳超时时清理房间成员。
- 新增 `scripts/verify-danmaku-websocket.ps1`：
  - 登录获取 JWT。
  - 创建两个 WebSocket 客户端连接同一个视频房间。
  - 验证 ping/pong。
  - 验证一个客户端发送弹幕后，两个客户端都收到同一房间广播。

### 当前边界

- 本次只实现内存广播。
- 暂时不落库。
- 暂时不做历史弹幕接口。
- 暂时不做 Redis 限流。
- 没有引入 Canal。

### 代码思想

- 不使用 Spring WebSocket，弹幕长连接由 Netty 独立端口承载。
- Spring Boot 负责启动主应用和创建 Bean，Netty Server 通过 Spring Bean 复用已有能力：
  - JWT 解析。
  - 视频状态校验。
  - JSON 序列化。
- `ChannelGroup` 适合管理同一房间的多个连接。
- `Channel.attr(...)` 保存连接上下文：
  - `videoId`
  - `userId`
- 断开连接时不需要查数据库，直接根据 Channel Attribute 从房间移除。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
  - 结果：构建成功，Spring Boot 上下文测试通过。
  - 测试日志确认 Netty 弹幕服务可随 Spring Boot 启动：
    - `Danmaku Netty WebSocket server started on port 8090`
- 已启动本地应用：
  - Spring Boot 端口：`8080`
  - Netty WebSocket 端口：`8090`
- 已执行：

```powershell
scripts/verify-danmaku-websocket.ps1 -VideoId 11
```

- 验证结果：
  - 两个客户端都成功连接：
    - `clientAState=Open`
    - `clientBState=Open`
  - `clientA` 发送 `{"type":"PING"}` 后收到：

```json
{"type":"PONG"}
```

  - `clientA` 发送弹幕后，`clientA` 和 `clientB` 都收到同一条广播：

```json
{
  "type": "DANMAKU",
  "videoId": 11,
  "userId": 2,
  "content": "hello danmaku",
  "createdAt": "..."
}
```

  - 服务端日志确认：
    - Channel 加入 `videoId=11` 房间。
    - 广播时 `receivers=2`。
    - 客户端关闭后从房间移除。

### 面试讲解点

- 为什么弹幕用 Netty：
  - 弹幕是高并发长连接场景，Netty 对连接、事件循环、编解码和心跳控制更细。
- 为什么不用 Spring WebSocket：
  - 当前设计目标是展示独立 Netty 长连接服务能力。
  - WebSocket 端口和 HTTP API 端口解耦。
- 为什么用 `videoId -> ChannelGroup`：
  - 一个视频就是一个弹幕房间。
  - 同一房间内广播只需要向对应 ChannelGroup 写消息。
- 为什么握手阶段校验 JWT：
  - 避免匿名连接进入弹幕房间。
  - 连接建立后 Channel 上天然携带用户身份。
- 后续增强方向：
  - 弹幕落库。
  - 历史弹幕查询接口。
  - Redis 发送频率限制。
  - 弹幕内容审核。
  - 多实例部署时使用 Redis Pub/Sub 或 MQ 做跨节点广播。

## 2026-06-11 分片上传、断点续传和 MD5 秒传

### 本次目标

在不破坏原有 `POST /api/videos/upload` 普通上传接口的前提下，新增大文件上传入口：

- 基于完整文件 MD5 判断是否可以秒传。
- 使用 Redis 记录每个 `uploadId` 已经上传的分片。
- 使用 Redisson 按 `fileMd5` 加分布式锁，避免多个请求重复合并同一个文件。
- 分片合并完成后复用现有视频上传后的入库、转码任务创建和 RabbitMQ 异步转码链路。

### 新增接口

- `POST /api/videos/multipart/init`
  - 初始化分片上传。
  - 如果 `fileMd5` 已经存在于 `uploaded_files`，直接创建视频记录和转码任务，返回 `instantUploaded=true`。
  - 如果不存在，返回新的 `uploadId`。

- `POST /api/videos/multipart/chunk`
  - 上传单个分片。
  - 参数：`uploadId`、`chunkIndex`、`file`。
  - 分片对象临时保存到 MinIO：`multipart/{uploadId}/{chunkIndex}.part`。
  - 上传成功后写入 Redis Set。

- `GET /api/videos/multipart/{uploadId}`
  - 查询断点续传进度。
  - 返回已经上传的分片下标集合。

- `POST /api/videos/multipart/complete`
  - 校验分片是否全部上传。
  - 使用 Redisson 获取 `streamhub:upload:merge:{fileMd5}` 锁。
  - 下载 MinIO 临时分片到本地临时文件并按顺序合并。
  - 重新计算合并后文件 MD5，和前端传入的 `fileMd5` 对比。
  - 上传完整原始视频到 MinIO。
  - 写入 `uploaded_files`。
  - 调用 `VideoUploadService#createVideoAfterOriginalStored(...)` 复用原有转码链路。

### 新增 Redis Key

```text
streamhub:upload:{uploadId}:meta
```

Hash，保存上传任务元信息：

- `userId`
- `fileMd5`
- `fileName`
- `fileSize`
- `chunkSize`
- `totalChunks`
- `contentType`

```text
streamhub:upload:{uploadId}:chunks
```

Set，保存已经上传成功的分片下标。

```text
streamhub:upload:merge:{fileMd5}
```

Redisson 分布式锁，防止重复合并同一个完整文件。

### 新增表

新增 `uploaded_files`：

- `file_md5` 唯一索引，用于秒传判断。
- `object_key` 保存完整原始文件在 MinIO 的地址。
- `status` 当前只使用 `COMPLETED`。

这张表表达的是“物理文件是否已经存在”，而 `videos` 表表达的是“用户创建的视频业务记录”。两者分开后，同一个原始文件可以被多个视频记录复用。

### 代码思想

- 秒传不是跳过业务流程，而是跳过重复上传文件。
- 只要 `fileMd5` 命中 `uploaded_files`，后端就可以复用已有 `objectKey` 创建新的视频记录和转码任务。
- Redis 只保存临时上传进度，不作为最终业务事实。
- MySQL 的 `uploaded_files` 才是秒传判断的长期依据。
- Redisson 锁只锁 `fileMd5` 维度，避免同一个文件重复合并，不影响不同文件并发上传。
- 分片上传完成后没有复制转码逻辑，而是抽取 `VideoUploadService#createVideoAfterOriginalStored(...)`，普通上传和分片上传共用同一套后续链路。

### 当前边界

- 当前使用“下载分片到本地再合并”的方式，逻辑更直观。
- 后续可以优化为 MinIO 服务端 compose，减少后端磁盘和网络 IO。
- 当前没有做上传任务持久化，Redis 过期后需要重新初始化上传。
- 当前没有做分片 MD5 校验，只在最终合并后校验完整文件 MD5。

### 本次验证

- 已执行 `.\mvnw.cmd test`。
- 已同步本地 MySQL `uploaded_files` 表。
- 已新增并执行：

```powershell
scripts/verify-multipart-upload.ps1
```

- 验证结果：
  - 初始化分片上传返回 `uploadId`。
  - 两个分片上传成功。
  - 查询进度返回 `[0, 1]`。
  - 完成上传后创建视频记录。
  - 使用同一个 `fileMd5` 再次初始化，返回 `instantUploaded=true`，说明秒传命中。

## 2026-06-11 Elasticsearch 视频搜索

### 本次目标

新增视频搜索能力，同时保持 MySQL 作为业务主库：

- Elasticsearch 只负责全文检索和相关性排序。
- MySQL 仍然负责视频详情、状态、播放地址和计数字段。
- 搜索接口优先查 Elasticsearch，失败时降级到 MySQL LIKE。

### 新增配置

`deploy/docker-compose.yml` 新增 Elasticsearch：

```yaml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:8.15.3
  environment:
    discovery.type: single-node
    xpack.security.enabled: "false"
```

`application-dev.yml` 新增：

```yaml
streamhub:
  search:
    elasticsearch-endpoint: http://localhost:9200
    video-index-name: streamhub_videos
```

### 新增接口

```http
GET /api/videos/search?keyword={keyword}&pageNo=1&pageSize=10
```

返回结构仍然是：

```text
Result<PageResponse<VideoDetailResponse>>
```

这样前端可以和视频列表、热门榜使用同一套分页模型。

### 索引同步时机

- 应用启动后，尝试把已有 `PUBLISHED` 视频同步到 Elasticsearch。
- 视频转码成功并发布后，调用 `VideoSearchService#indexPublishedVideo(...)` 写入索引。
- Elasticsearch 写入失败只记录日志，不影响视频发布成功。

### 搜索流程

```text
Controller
  -> VideoSearchService
  -> ElasticsearchVideoSearchClient
  -> 得到 videoId 列表
  -> VideoService 回 MySQL 查询视频详情
  -> 按 ES 命中顺序返回
```

### 降级策略

如果 Elasticsearch 不可用：

```text
VideoSearchService
  -> catch RuntimeException
  -> fallback to VideoService#searchPublishedFromDatabase(...)
```

也就是退回：

```sql
title LIKE keyword OR description LIKE keyword
```

这样搜索引擎挂掉时，搜索接口仍然可用，只是相关性排序和全文检索能力下降。

### 代码思想

- 不把 Elasticsearch 当主库，避免搜索索引和业务事实强绑定。
- 搜索索引只保存可检索字段，如 `videoId`、`title`、`description`、`status`、`createdAt`。
- 搜索结果只信任 ES 的命中顺序，不直接信任 ES 的完整业务数据。
- 最终返回仍从 MySQL 组装 `VideoDetailResponse`。

### 本次验证

- 已执行 `.\mvnw.cmd test`，Spring Boot 上下文启动通过。
- 已执行：

```powershell
scripts/verify-search.ps1 Verification
```

- 当前本地 Elasticsearch 未启动时，搜索接口成功降级到 MySQL LIKE，并返回已发布视频分页结果。

## 2026-06-11 Vue3 前端试用版

### 本次目标

新增一个轻量前端，让后端功能可以直接在浏览器里试用。

### 技术选型

- Vite
- Vue3
- hls.js

hls.js 用于在 Chrome / Edge 中播放后端 HLS 地址。

### 前端目录

```text
web/
  index.html
  package.json
  vite.config.js
  src/main.js
  src/styles.css
```

### 已覆盖功能

- 登录 / 注册。
- 视频最新列表。
- 热门列表。
- 我的视频。
- Elasticsearch 搜索接口。
- 普通视频上传。
- HLS 播放。
- Netty WebSocket 弹幕连接。
- 发送弹幕并在播放画面上显示。

### 本地运行方式

后端：

```powershell
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
cd web
npm install
npm run dev -- --port 5173
```

访问：

```text
http://127.0.0.1:5173
```

### 代码思想

- Vite 代理 `/api` 到 `http://localhost:8080`，避免额外配置后端 CORS。
- WebSocket 弹幕直接连接 `ws://localhost:8090/ws/danmaku`。
- 播放页先调用 `/api/videos/{videoId}/play` 获取 HLS 地址，再交给 hls.js 播放。
- 弹幕以前端覆盖层渲染，后端只负责广播消息。

### 当前边界

- 当前是试用版前端，不是完整产品 UI。
- 分片上传暂未做进页面，仍可通过脚本验证。
- 评论、点赞、收藏暂未做前端入口。
- 页面路由暂未拆分，当前是单页演示应用。
