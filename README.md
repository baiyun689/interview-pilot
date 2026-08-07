# InterviewPilot

InterviewPilot 是一个基于 Spring Boot 的 AI 自适应技术面试系统。它能够分析候选人简历，根据固定面试方向或自定义岗位生成面试计划，在多轮问答中动态决定追问、换题、难度调整和结束时机，并在面试完成后异步生成评估报告。

## 核心能力

- 支持 TXT、PDF、DOCX 简历上传、文本提取和结构化分析。
- 内置 Java 后端、Python 后端、前端、AI Agent、测试开发、算法、系统设计和自定义岗位 8 个面试 Skill。
- 固定面试方向可以直接使用，也可以通过可选 JD 补充要求；自定义岗位必须提供岗位名称和 JD。
- 创建面试时固化 Skill、模型、岗位要求和面试计划快照，避免后续配置变化影响历史面试。
- 使用 POST SSE 渐进返回接收状态、评分反馈、决策和下一题。
- 模型只提出建议，Java 决策策略负责限制追问次数、能力覆盖、置信度、难度范围和总轮次。
- 面试结束后通过 RabbitMQ 可靠异步生成报告，支持重试、死信和人工恢复。
- 提供面试历史、报告查询、模型切换、健康检查、指标和 Trace ID。

当前未实现登录鉴权、多租户、计费、语音 ASR/TTS、在线 RAG 检索、知识库管理、面试预约、导出和 WebSocket。Skill 中保留了部分 RAG 元数据和参考资料映射，但运行时 RAG 仍处于关闭状态。

## 技术栈

- 后端：Java 21、Spring Boot 4、Spring MVC、Spring Data JPA、Hibernate、Flyway、Spring AI
- 数据库：MySQL 8.4
- 消息队列：RabbitMQ 4
- 缓存与并发协调：Redis 7.4、Redisson
- 前端：React 18、TypeScript、Vite、Vitest
- 网关：Nginx
- 测试：JUnit 5、Testcontainers、WireMock、Testing Library
- 部署：Docker、Docker Compose


## 架构与后端设计

### MySQL 作为业务事实来源

MySQL 持久化简历、分析结果、岗位要求、Skill 快照、面试计划、会话、轮次、回答尝试、报告和异步任务状态。Redis 标记和 RabbitMQ 消息只承担协调职责，消费者处理任务前始终重新检查 MySQL 状态。

Skill、Provider、Model 和 InterviewPlan 都会在创建面试时生成不可变快照。即使之后修改默认模型或 Skill 文件，已经开始的面试仍使用创建时的版本。

### 可控的 AI 决策

模型返回评分结果和建议决策，但不会直接控制业务状态。每轮决策包含两个相互独立的维度：

- `nextStep`：`FOLLOW_UP`、`NEXT_TOPIC`、`FINISH`
- `difficultyAdjustment`：`INCREASE`、`KEEP`、`DECREASE`

Java 策略会校验模型建议，并根据置信度、必考能力覆盖、连续追问次数和轮次预算生成最终决策。因此可以出现 `FOLLOW_UP + INCREASE`、`NEXT_TOPIC + DECREASE` 等合法组合。

所有结构化模型输出都必须反序列化为受约束的 Java Record；字段缺失、枚举非法、分数越界或结构错误都会被识别为无效输出，而不是直接进入数据库。

### 并发与幂等

每次回答都要求客户端提供 UUID `requestId`。后端通过唯一键、答案指纹、回答尝试记录和 JPA `@Version` 实现并发保护：

- 相同 `requestId` 和相同答案可以重放已有结果。
- 相同 `requestId` 携带不同答案会被拒绝。
- 只有持有当前版本的处理者能够完成轮次、创建下一题或触发报告任务。
- SSE 连接断开不会自动重复提交；前端先通过 GET 恢复持久化状态，再由用户显式发起新的重试。

### 事务与模型调用边界

耗时的大模型调用不会占用数据库事务：

1. 短事务校验状态并领取处理权。
2. 在事务外调用模型并校验结构化结果。
3. 新短事务重新检查版本和所有权，再持久化结果并推进状态。

这种方式减少了数据库锁占用，同时允许通过版本围栏丢弃已经失去所有权的迟到结果。

### RabbitMQ 可靠异步任务

简历分析和面试报告通过数据库任务表与 RabbitMQ 协作处理。任务发布使用 Publisher Confirm，并配置分级延迟重试队列和 DLQ。由于数据库提交和消息发布无法形成一个本地原子事务，系统采用任务表扫描、周期重发和消费者幂等实现至少一次投递。

### Redis 的职责

Redis 只承担三类短期协调职责：

- API 固定窗口限流。
- 全局大模型并发租约。
- 异步 Worker 和回答处理的短期 Claim。

Redis 不保存面试业务事实。即使锁或标记因异常丢失，MySQL 中的任务状态、尝试次数和版本字段仍是最终判断依据。

## 环境要求

推荐使用 Docker Compose 一键启动，需要：

- Docker Desktop 或其他支持 Compose v2 的 Docker 环境
- 至少一个可用的大模型 Provider API Key

本地开发还需要：

- JDK 21
- Node.js 22
- Corepack
- 可连接的 MySQL、Redis 和 RabbitMQ

项目已经包含 Gradle Wrapper，不需要全局安装 Gradle。

## 配置

复制环境变量示例：

```bash
cp .env.example .env
```

Windows PowerShell 可以使用：

```powershell
Copy-Item .env.example .env
```

修改 `.env` 中的数据库、RabbitMQ 密码，并至少启用一个模型 Provider。不要提交 `.env`。

每个 Provider 都有以下配置：

- `*_BASE_URL`
- `*_API_KEY`
- `*_MODEL`
- `*_ENABLED`
- `*_TIMEOUT`

当前支持的 Provider ID：

- `dashscope`
- `deepseek`
- `kimi`

三者均通过 OpenAI-compatible Chat API 接入。启用 Provider 时需要填写完整的 API Key、Base URL 和 Model，并让 `AI_DEFAULT_PROVIDER` 指向一个已启用的 Provider。

`LLM_LEASE` 必须大于最长 Provider 超时时间的两倍，因为一次结构化调用可能包含一次重试。其他数据库、Redis、RabbitMQ、端口、模型并发量和结构化输出重试配置见 [.env.example](.env.example)。

Compose 使用非 `guest` RabbitMQ 用户 `interview_pilot`。RabbitMQ 默认限制 `guest` 只能从 localhost 登录，因此不能用于 App 容器与 RabbitMQ 容器之间的认证。

## 使用 Docker Compose 启动

```bash
docker compose up -d --build
```

启动完成后：

- 前端页面：`http://localhost:3000`
- 健康检查：`http://localhost:3000/actuator/health`
- RabbitMQ 管理页面：`http://localhost:15672`
- MySQL：`localhost:3306`
- Redis：`localhost:6379`

Nginx 是 Compose 中唯一公开的应用入口，同时代理前端、`/api` 和 Actuator。Spring Boot 的 8080 端口只在 Docker 内部网络开放，避免外部客户端伪造限流所依赖的转发地址。Nginx 会覆盖 `X-Forwarded-For`，Spring 再通过 Framework Forward Headers 解析可信来源地址。

查看容器状态和日志：

```bash
docker compose ps
docker compose logs -f app frontend mysql redis rabbitmq
```

停止服务：

```bash
docker compose down
```

只有明确需要删除 MySQL、Redis 和 RabbitMQ 数据卷时才使用：

```bash
docker compose down -v
```

可以在 `.env` 中修改公开端口，避免与本机已有服务冲突。

## 本地开发

只通过 Docker 启动基础设施：

```bash
docker compose up -d mysql redis rabbitmq
```

启动后端：

```bash
./gradlew bootRun
```

Windows PowerShell：

```powershell
.\gradlew.bat bootRun
```

启动前端：

```bash
cd frontend
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

Vite 会将 `/api` 代理到 `localhost:8080`。Spring Boot 会读取项目根目录中可选的 `.env` 文件。

## 使用与 API 流程

1. 调用 `POST /api/resumes` 上传 TXT、PDF 或 DOCX 简历。响应包含持久化分析任务 ID；标准化内容相同的重复简历会返回已有简历和任务。
2. 轮询 `GET /api/tasks/{taskId}`，或查询 `GET /api/resumes/{id}`，直到简历状态变为 `READY`。
3. 调用 `GET /api/interview-skills` 获取固定面试方向。
4. 调用 `POST /api/interviews` 创建面试，请求包含 `resumeId`、`skillId`、岗位名称、可选或必填 JD、难度、5～15 轮预算和可选 `providerId`。
5. 固定 Skill 可以不填写 JD；选择 `custom` 时必须提供岗位名称和 JD。后端会固化 Skill、Provider、Model、岗位要求和面试计划快照，并生成首题。
6. 调用 `POST /api/interviews/{sessionId}/answers/stream` 提交 `{requestId, answer}`，SSE 会依次发送 `ACCEPTED`、`FEEDBACK`、`DECISION`、可选的 `NEXT_QUESTION` 和 `COMPLETED`。
7. 当 Java 策略或轮次预算结束面试后，会话进入 `EVALUATING`，同时创建持久化报告任务。
8. 调用 `GET /api/interviews/{sessionId}/report` 查询最终报告。

其他接口支持查询面试历史、查看 Provider、测试 Provider 连接、切换默认模型、查询异步任务以及重试失败或进入死信状态的任务。

主要 Controller：

- [简历 API](src/main/java/interview/pilot/resume/api/ResumeController.java)
- [面试 API](src/main/java/interview/pilot/interview/api/InterviewController.java)
- [Skill API](src/main/java/interview/pilot/interview/api/InterviewSkillController.java)
- [异步任务 API](src/main/java/interview/pilot/async/api/AsyncTaskController.java)
- [模型 Provider API](src/main/java/interview/pilot/ai/provider/AiProviderController.java)

## 五分钟演示

1. 打开 `/actuator/health`，展示 MySQL、Redis 和 RabbitMQ 健康状态。
2. 进入模型设置页，展示可切换 Provider，并测试当前模型连接。
3. 上传一份简短简历，说明分析任务如何通过 RabbitMQ 异步执行并最终进入 `READY`。
4. 选择 Java 后端或 AI Agent Skill，展示固定方向可选 JD、自定义岗位必填 JD，以及创建后生成的 Skill/Model/Plan 快照。
5. 回答两道题，展示 SSE 渐进反馈，以及互相独立的 `nextStep` 和 `difficultyAdjustment`。
6. 说明模型只提出建议，Java 策略负责轮次预算、必考能力、置信度和追问上限。
7. 完成面试后展示异步报告和面试历史。
8. 使用准备好的 API 请求重复提交相同答案和 `requestId`，演示后端幂等；前端则采用 GET 恢复和显式新 ID 重试。

核心业务旅程测试使用 WireMock 支撑的测试 Gateway 替代真实模型，能够在没有 API Key 的情况下验证 Prompt 分类、服务编排、状态流转和持久化：

[InterviewJourneyIT](src/test/java/interview/pilot/e2e/InterviewJourneyIT.java)

该测试不覆盖 HTTP Controller、SSE 传输、RabbitMQ Listener/Dispatcher 和真实 `SpringAiGateway`，这些边界由独立测试覆盖。

## 测试与验证

后端测试：

```bash
./gradlew test
./gradlew test --tests interview.pilot.e2e.InterviewJourneyIT
```

Windows PowerShell：

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests interview.pilot.e2e.InterviewJourneyIT
```

前端测试与构建：

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm exec vitest run
pnpm build
```

交付与静态检查：

```bash
docker compose config
git diff --check
```

当前已验证：

- 前端 7 个测试文件、59 项测试通过，TypeScript 编译和生产构建成功。
- Skill Catalog、中文 Prompt、面试创建和 AI Gateway 等相关单元测试通过。
- 基于 Testcontainers 的面试创建持久化、并发答题和 RabbitMQ 报告处理集成测试通过。
- `bootJar` 构建、`docker compose config` 和 `git diff --check` 通过。

项目不对 QPS、延迟、用户量、成本节省或生产使用情况作未经测量的声明。

重点测试：

- [回答并发与幂等](src/test/java/interview/pilot/interview/application/SubmitAnswerConcurrencyIT.java)
- [RabbitMQ 重试与死信](src/test/java/interview/pilot/async/messaging/RabbitRetryIT.java)
- [简历分析 Listener](src/test/java/interview/pilot/async/resume/ResumeAnalysisListenerIT.java)
- [报告 Listener](src/test/java/interview/pilot/async/report/InterviewReportListenerIT.java)
- [Java 决策策略](src/test/java/interview/pilot/interview/domain/InterviewDecisionPolicyTest.java)
- [SSE 行为](src/test/java/interview/pilot/interview/api/InterviewSseControllerTest.java)

## 常见问题

- **没有可用模型：** 为至少一个 Provider 设置非空 API Key 和 `*_ENABLED=true`，并让 `AI_DEFAULT_PROVIDER` 使用相同 ID。Provider Client 在启动时组装，修改后需要重启 App。
- **启动时提示 LLM Lease 无效：** 增大 `LLM_LEASE`，或降低已启用 Provider 的超时时间。Lease 必须大于最长超时时间的两倍。
- **App 一直处于 unhealthy：** 查看 `docker compose logs app mysql redis rabbitmq`。Actuator 会检查基础设施健康状态，凭据错误或依赖不可用都会让 App 保持不健康。
- **简历或报告一直 pending：** 查询任务接口并查看 RabbitMQ 管理页面。失败或进入 `DEAD` 的任务可以在 Redis Claim 释放后，通过 `POST /api/tasks/{taskId}/retry` 重试。
- **SSE 响应看起来被缓冲：** 通过项目自带的 Nginx 访问。它已经为 `/api/` 关闭代理缓冲并延长读写超时；其他反向代理也需要保留这些配置。
- **端口被占用：** 在 `.env` 中修改 `FRONTEND_PORT`、`MYSQL_PORT`、`REDIS_PORT`、`RABBITMQ_PORT` 或 `RABBITMQ_MANAGEMENT_PORT`。Spring Boot 端口在 Compose 中不会公开到宿主机。
- **Docker 拉取镜像失败：** 如果日志出现镜像站 EOF 或超时，这是 Docker 镜像源网络问题，不是项目构建错误。检查 Docker Desktop 的 Registry Mirror 配置后重试。
