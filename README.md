# InterviewPilot

InterviewPilot 是一个基于 Spring Boot 的 AI 技术面试系统。它能够分析候选人简历，根据固定面试方向（Preset）或自定义岗位 JD 生成结构化题卡，按「自我介绍 → 基础 → 项目经历 → 场景权衡」的固定阶段推进主问题与动态追问，支持文本、录音和实时语音三种作答方式，并在面试完成后异步生成评估报告。

## 项目展示

### 开始面试

可选择候选人简历、面试方向、岗位要求、难度、面试规模（Quick/Standard/Deep）、模型和知识库，创建一场可追溯的结构化面试。

![开始面试：配置岗位、模型和知识库](assets/images/interview-create.png)

### 简历分析

对上传的简历进行多维度评分，并给出内容、技能和项目经历等高优先级优化建议。

![简历分析：评分与优化建议](assets/images/resume-analysis.png)

### 面试记录

集中展示每场面试的方向、模型、难度、当前进度与创建时间，并支持继续未完成的面试。

![面试记录：会话列表与进度](assets/images/interview-history.png)

### 知识库

支持创建知识库、上传 PDF/TXT/Markdown 文档、查看索引状态和分段数量，并可对文档重建索引或删除。

![知识库：文档上传与索引管理](assets/images/knowledge-base.png)

### 模型设置

统一管理可用大模型 Provider，查看启用状态、测试连接，并切换后续面试使用的默认模型。

![模型设置：Provider 管理与默认模型切换](assets/images/model-settings.png)

### 面试过程

面试官按题卡依次提出主问题，并结合候选人回答与 RAG 上下文动态生成追问；固定流程策略依据主问题数量与每卡追问配额决定继续追问、进入下一主问题或结束面试。

![面试过程：问题、回答与动态反馈](assets/images/interview-session.png)

### 语音面试

实时语音对话保持一条 WebSocket：流式识别内容实时回显但不会自动发送，候选人说完点「提交回答」手动确认；面试官以语音播报下一题，中央状态球用动态话筒/声波呈现聆听、思考与播报状态，并可随时回退到录音转写模式。

![语音面试：实时对话、状态动效与手动确认提交](assets/images/interview-voice-realtime.png)

### 面试报告

面试结束后生成综合评分、能力维度、优势与改进方向，帮助候选人定位后续学习重点。

![面试报告：综合评分与能力评估](assets/images/interview-report.png)

## 功能特性

- 支持 TXT、PDF、DOCX 简历上传、文本提取和结构化分析。
- 内置 Java 后端、Python 后端、前端、AI Agent、测试开发、算法、系统设计和自定义岗位 8 个面试 Skill。
- 固定面试方向可以直接使用，也可以通过可选 JD 补充要求；自定义岗位必须提供岗位名称和 JD。
- 创建面试时固化 Skill、模型、岗位要求和面试计划快照，避免后续配置变化影响历史面试。
- 提交答案通过 POST SSE 渐进返回 `ACCEPTED`、`PROCESSING`、`RESULT`（下一题/结束）状态，异常时返回 `ERROR`。
- 出题阶段由结构化模型一次性生成题卡；运行期由纯 Java 的固定流程策略按阶段、主问题数量与每卡追问配额决策，追问不占用主问题预算，模型不直接改写业务状态。
- 支持知识库上传、异步索引、Qdrant 向量召回和面试 RAG 上下文注入。
- 面试结束后通过 RabbitMQ 可靠异步生成报告，支持重试、死信和人工恢复。
- 语音面试支持实时对话（单条 WebSocket、流式 ASR、服务端 VAD 自动断句、实时字幕、候选人手动确认提交、实时 TTS）与 MediaRecorder 录音转写两种模式，实时作答复用同一套回合引擎与幂等控制。
- 提供面试历史、报告查询、模型切换、健康检查、指标和 Trace ID。

当前未实现计费、面试预约和导出。知识库/RAG 需要显式开启 `KNOWLEDGE_ENABLED=true` 并配置 Embedding API Key；语音面试通过 `VOICE_ENABLED=true` 开启（实时 WebSocket 对话默认随之一并启用，录音转写作为兜底）。

## 技术栈

- 后端：Java 21、Spring Boot 4、Spring MVC、WebSocket、Spring Data JPA、Hibernate、Flyway、Spring AI
- 数据库：MySQL 8.4
- 消息队列：RabbitMQ 4
- 向量库：Qdrant
- 语音：DashScope 实时 ASR/TTS（Qwen3-Realtime，WebSocket），MediaRecorder 非实时转写兜底
- 缓存与并发协调：Redis 7.4、Redisson
- 前端：React 18、TypeScript、Vite、Vitest
- 网关：Nginx
- 测试：JUnit 5、Testcontainers、WireMock、Testing Library
- 部署：Docker、Docker Compose

## 架构与后端设计

### MySQL 作为业务事实来源

MySQL 持久化简历、分析结果、岗位要求、Skill 快照、面试计划、会话、轮次、回答尝试、报告和异步任务状态。Redis 标记和 RabbitMQ 消息只承担协调职责，消费者处理任务前始终重新检查 MySQL 状态。

Skill、Provider、Model 和 InterviewPlan 都会在创建面试时生成不可变快照。即使之后修改默认模型或 Skill 文件，已经开始的面试仍使用创建时的版本。

### 固定面试流程与受控的 AI 输出

题卡在出题阶段由结构化模型一次性生成；运行期的题目推进不交给模型，而由纯 Java 的 `FixedInterviewFlowPolicy` 决策，模型不直接改写业务状态。其规则如下：

- 阶段固定为 `SELF_INTRODUCTION → FUNDAMENTALS → PROJECT_EXPERIENCE → SCENARIO_TRADEOFF → END`。
- 面试规模分 Quick/Standard/Deep 三档，分别规划 6/9/12 个主问题（各阶段数量固定），追问另计、不占用主问题预算。

每张题卡有独立追问配额：未用完则继续 `FOLLOW_UP`，用完进入同阶段下一主问题，阶段主问题问完即切换阶段，全部阶段结束即 `END`；自我介绍阶段不产生追问。

所有结构化模型输出（题卡、追问、报告）都必须反序列化为受约束的 Java Record；字段缺失、枚举非法或结构错误都会被识别为无效输出，而不是直接进入数据库；追问生成失败时回退到题卡内置的兜底追问，不阻断面试流程。

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

简历分析、知识库索引和面试报告通过数据库任务表与 RabbitMQ 协作处理。任务发布使用 Publisher Confirm，并配置分级延迟重试队列和 DLQ。由于数据库提交和消息发布无法形成一个本地原子事务，系统采用任务表扫描、周期重发和消费者幂等实现至少一次投递。

### 知识库与 RAG

知识库文档上传后会先进入 `PROCESSING`，后台任务负责解析文件、切分文本、写入 Qdrant，并在成功后标记为 `READY`。文档列表会返回 `PENDING`、`PROCESSING`、`READY`、`FAILED`、`DELETING` 等可见状态，前端会对索引中的文档轮询刷新。

创建面试时如果选择知识库，系统只会把当前 `READY` 文档纳入召回范围；每轮生成题目或评估答案前都会按用户、知识库、文档和索引版本过滤召回结果，避免跨用户、跨知识库或旧版本内容进入上下文。删除文档会清理文件和向量，并把旧索引消息视为终态，避免晚到消息反复重试。

### Redis 的职责

Redis 只承担三类短期协调职责：

- API 固定窗口限流。
- 全局大模型并发租约。
- 异步 Worker 和回答处理的短期 Claim。

Redis 不保存面试业务事实。即使锁或标记因异常丢失，MySQL 中的任务状态、尝试次数和版本字段仍是最终判断依据。

## 快速开始

### 环境要求

推荐使用 Docker Compose 一键启动，需要：

- Docker Desktop 或其他支持 Compose v2 的 Docker 环境
- 至少一个可用的大模型 Provider API Key

本地开发还需要：

- JDK 21
- Node.js 22
- Corepack
- 可连接的 MySQL、Redis 和 RabbitMQ

项目已经包含 Gradle Wrapper，不需要全局安装 Gradle。

### 使用 Docker Compose 启动

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

### 本地开发

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

## 配置说明

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

启用知识库/RAG 时还需要：

- `KNOWLEDGE_ENABLED=true`
- `DASHSCOPE_EMBEDDING_API_KEY=你的 DashScope Key`
- Qdrant 连接配置，Docker Compose 默认使用内置 `qdrant` 服务

## 语音面试（Voice Interview）

语音面试提供两种模式，均由 `VOICE_ENABLED` 总开关控制，关闭时文字面试完全不受影响。

### 实时对话模式（默认）

开启语音后默认使用实时对话：浏览器与后端保持**一条 WebSocket**（`/ws/voice-interview/{sessionId}`，握手通过 `?token=` 携带 JWT 并校验会话归属）。麦克风采集 16kHz/16bit 单声道 PCM，以 100ms 一帧持续上行；后端转发 DashScope 实时 ASR（`qwen3-asr-flash-realtime`，服务端 VAD 静音 800ms 仅用于自动断句），partial/final 文本作为实时字幕持续回显并在服务端累积，**默认不会自动提交**——候选人确认内容后点「提交回答」（前端发送 `submit` 控制帧，并可携带最后尚未落定的半句），后端才以 `VOICE_REALTIME` 模式交给同一套回合引擎（claim/process），再由实时 TTS（`qwen3-tts-flash-realtime`）整段合成 24kHz PCM、封 WAV 经同一连接回推播放。如需「停顿后自动发送」，可设 `app.voice.realtime.conversation.auto-submit=true` 恢复。

实时链路为**半双工**：AI 播报期间抑制麦克风上行并设置冷却时间以避免回声；连接空闲 4 分 30 秒提醒、5 分钟关闭。实时模式复用录音模式的总开关与同一把 `DASHSCOPE_SPEECH_API_KEY`，不引入独立凭据；细项通过 `app.voice.realtime.*` 配置且均有默认值（路径、ASR/TTS 模型、VAD、提交方式、空闲时长、单帧上限等），一般无需调整，其中 `conversation.auto-submit` 默认 `false`（手动确认提交），`conversation.debounce-ms` 仅在自动提交模式下生效。

### 录音模式（文件式、可编辑转写，作为兜底）

录音模式是可选的增强输入方式，默认关闭。打开后，面试官提问会先由 TTS 异步合成语音
（可降级），考生用浏览器 `MediaRecorder` 按题录音上传，后端调用 DashScope 非实时 ASR
异步转写，考生确认（可修改错别字和技术词）转写文本后提交答案。

这是**半双工、文件式**的语音链路，不是实时通话：一轮只处理一段完整录音，转写任务在
RabbitMQ worker 内执行；确认后的文本才是领域事实，报告只使用确认文本。链路设计为
可恢复——上传幂等（`uploadRequestId`）、任务 epoch fencing、乐观锁、延迟重试与 DLQ、
私有媒体存储，断线或重复消息都不会破坏状态机。

### 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `VOICE_ENABLED` | `false` | 总开关；关闭时文字面试完全不受影响，健康检查也不会降级 |
| `DASHSCOPE_SPEECH_API_KEY` | （空） | DashScope 语音 API Key，ASR 必需 |
| `DASHSCOPE_SPEECH_BASE_URL` | `https://dashscope.aliyuncs.com/api/v1` | ASR/TTS 共用端点（TTS 复用 ASR 凭据） |
| `DASHSCOPE_ASR_MODEL` | `fun-asr-flash-2026-06-15` | ASR 模型 |
| `DASHSCOPE_ASR_TIMEOUT` | `60s` | ASR 调用超时 |
| `DASHSCOPE_TTS_MODEL` | `cosyvoice-v3-flash` | TTS 模型 |
| `DASHSCOPE_TTS_VOICE` | `longanyang` | TTS 音色 |
| `DASHSCOPE_TTS_TIMEOUT` | `30s` | TTS 调用超时 |
| `VOICE_FILES_ROOT` | `./data/voice` | 媒体根目录（Compose 中是 `voice_files` 卷） |
| `VOICE_MAX_UPLOAD_BYTES` | `8388608` | 单次上传上限 8 MiB（模块内流式强制） |
| `VOICE_MAX_RECORDING_DURATION` | `5m` | 最长录音时长 |
| `VOICE_MEDIA_RETENTION` | `7d` | 媒体保留期 |
| `VOICE_CLEANUP_INTERVAL` | `PT10M` | 清理调度周期 |
| `VOICE_CLEANUP_INITIAL_DELAY` | `PT2M` | 清理首次延迟 |
| `VOICE_CLEANUP_BATCH_SIZE` | `50` | 每个清扫阶段每轮最多处理行数 |
| `VOICE_CLEANUP_STUCK_TASK_THRESHOLD` | `PT30M` | 卡住任务判定阈值 |
| `VOICE_CLEANUP_ORPHAN_GRACE` | `PT24H` | 孤儿文件最短存活时间 |
| `VOICE_CLEANUP_CLAIM_TTL` | `PT30M` | 清理运行 Redis claim 时长 |

启用语音：在 `.env` 中设置 `VOICE_ENABLED=true` 并填写 `DASHSCOPE_SPEECH_API_KEY`（ASR
必需；TTS 未配置时录音转写仍可用，问题无语音直接显示文本）。Compose 显式枚举全部变量，
无需 `env_file`。上传限流链路为「Nginx 9m → Spring multipart 8 MiB 文件 / 9 MiB 请求 →
模块 8 MiB 流式上限」，模块内的上限才是权威限制。

### API 流程

1. `GET /api/voice/capabilities` → `{enabled, supportedMimeTypes, maxRecordingSeconds, maxUploadBytes, ttsEnabled}`，前端据此决定是否展示录音入口。
2. `GET /api/interviews/{sessionId}/turns/{turnNo}/speech`：`PENDING/SYNTHESIZING` 返回 202 需轮询；`READY` 返回 200 与 `mediaUrl`；`NOT_AVAILABLE` 表示无语音（TEXT 会话或 TTS 未配置）。
3. `POST /api/interviews/{sessionId}/turns/{turnNo}/voice-recordings`（multipart：`uploadRequestId` 参数 + `audio` 文件）→ 202 返回 `{recordingId, status, transcriptionTaskId}`。同一 `uploadRequestId` 同内容重放返回原状态（幂等）；不同内容返回 409 `REQUEST_ID_CONFLICT`。
4. `GET /api/interviews/{sessionId}/voice-recordings/{recordingId}` 轮询：`READY` 后返回 `rawTranscript`（可编辑）与 `retryable` 标志。
5. `POST /api/interviews/{sessionId}/answers/stream` 提交 `{requestId, answer, inputMode: "VOICE", recordingId}`，SSE 流程与文字一致；转写确认/修改后的文本就是答案正文。
6. `GET /api/interviews/{sessionId}/speech/{speechId}/media` 支持 HTTP Range（206/416），供浏览器音频播放；`POST .../speech/retry` 手动重试合成。

### 失败与降级

- **ASR 失败**：录音进入 `FAILED`（带 `safe_error`）。前端可重录（新 `uploadRequestId`）
  或重试（原录音，仅当媒体仍在时）；重试耗尽后任务进入 `DEAD`，仍可手动重试。旧 epoch
  的迟到消息不能覆盖新结果。
- **TTS 失败**：该题无语音，直接显示问题文本，答题不受影响；可手动重试合成。
- **文字回退**：任何时刻都可放弃录音改用文字回答；前端对已上传录音做 best-effort
  discard，避免悬挂到保留期清理。
- **降级语义**：ASR 是语音输入的必要能力，TTS 是可降级的播放能力；语音故障只影响语音
  路径，不触碰面试状态机与报告事实。

### 媒体保留与清理

语音媒体（录音与合成语音）在 `VOICE_FILES_ROOT` 下按不可变键存储（`{userId}/{sessionId}/...`），
默认保留 7 天。清理扫描器每 10 分钟运行一次，采用「先标记状态、再删除文件、最后保存完成
事实」的 mark-before-delete 流程：过期录音/语音行先置为已清理状态，随后删除文件；孤儿文件
（崩溃残留、事务回滚残留）在满足 24 小时宽限期后回收，卡住的任务（转写中/合成中无进展）
超时后恢复为失败；文件已不存在、进程中断或重复消息都幂等，不会误删仍被引用且未过期的媒体。

### 非目标

- 实时对话为半双工、TTS 整句合成后整段播放，并非全双工通话或逐字流式播放；实时与录音两种模式共用同一套状态机与报告模型。
- 不根据声音特征评分：无声纹、情绪或作弊判断，语音只负责输入，不参与评分。
- 不支持 MaaS workspace 模式：仅支持标准 DashScope 域名 + API Key 认证。

### 验收命令

```text
.\gradlew.bat test
cd frontend
pnpm exec vitest run
pnpm build
cd ..
docker compose config
docker compose up -d --build
docker compose ps --all
git diff --check
```

### 手工验收清单

1. 创建 VOICE 标准面试。
2. 听到自我介绍问题；禁用自动播放后可手动播放。
3. 录制包含 JVM、Spring 事务、MySQL 等术语的回答。
4. 刷新页面，转写任务仍能恢复。
5. 修改一处转写文字并提交，数据库答案与修改后文本一致。
6. 完成追问，验证追问也有 TTS，且追问不计主问题数。
7. 模拟 ASR 失败，验证重录、重试和文字回退。
8. 模拟 TTS 失败，验证文字答题不受影响。
9. 完成面试并生成报告，报告只使用确认后的文字。

### 真实 Provider 冒烟测试

适配器的请求/响应结构由 WireMock 测试固定，但**尚未用真实 Key 在线验证**。上线前请用
真实 `DASHSCOPE_SPEECH_API_KEY` 跑以下命令（注意：DashScope 文档可能更新字段名，
以 <https://help.aliyun.com> 当前文档为准，必要时按返回结构调整）：

ASR（非实时文件识别，同步结果）：

```bash
curl -sS -X POST https://dashscope.aliyuncs.com/api/v1/services/audio/asr/recognition \
  -H "Authorization: Bearer $DASHSCOPE_SPEECH_API_KEY" \
  -F "model=fun-asr-flash-2026-06-15" \
  -F "file=@sample.mp3;type=audio/mpeg" \
  -F 'format=mp3'
# 预期: {"output":{"text":"...识别文本..."},"request_id":"...","usage":{...}}
```

TTS（CosyVoice 非实时，JSON 信封或裸音频两种形态之一）：

```bash
curl -sS -X POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation \
  -H "Authorization: Bearer $DASHSCOPE_SPEECH_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"cosyvoice-v3-flash","voice":"longanyang","input":{"text":"请用一分钟介绍自己。"}}'
# 预期: 202 + task_id（异步）或 200 音频流/{"output":{"audio":{"url":"临时URL"}}}
```

后端适配器已兼容「返回 `output.audio.url` 临时 URL（自动限流下载）」与「直接返回音频流」
两种形态；字段名如与返回不符，`safe_error` 会保留诊断信息（不会把 Provider 原文返回给
前端）。

## 使用与 API 流程

1. 调用 `POST /api/resumes` 上传 TXT、PDF 或 DOCX 简历。响应包含持久化分析任务 ID；标准化内容相同的重复简历会返回已有简历和任务。
2. 轮询 `GET /api/tasks/{taskId}`，或查询 `GET /api/resumes/{id}`，直到简历状态变为 `READY`。
3. 可选：调用 `POST /api/knowledge-bases` 创建知识库，`POST /api/knowledge-bases/{id}/documents` 上传文档，再轮询 `GET /api/knowledge-bases/{id}/documents`，直到需要使用的文档变为 `READY`。
4. 调用 `GET /api/interview-presets` 获取固定面试方向（Preset）。
5. 调用 `POST /api/interviews` 创建面试，请求包含 `resumeId`、`skillId`、岗位名称、可选或必填 JD、难度、面试规模（Quick/Standard/Deep，对应 6/9/12 个主问题，追问另计）、可选 `providerId` 和可选 `knowledgeBaseIds`。
6. 固定 Skill 可以不填写 JD；选择 `custom` 时必须提供岗位名称和 JD。后端会固化 Skill、Provider、Model、岗位要求和面试计划快照，并生成首题。
7. 调用 `POST /api/interviews/{sessionId}/answers/stream` 提交 `{requestId, answer, inputMode}`，SSE 会依次发送 `ACCEPTED`、`PROCESSING`、`RESULT`（含下一题或结束状态），异常时发送 `ERROR`。
8. 当 Java 策略或轮次预算结束面试后，会话进入 `EVALUATING`，同时创建持久化报告任务。
9. 调用 `GET /api/interviews/{sessionId}/report` 查询最终报告。

其他接口支持查询面试历史、查看 Provider、测试 Provider 连接、切换默认模型、查询异步任务以及重试失败或进入死信状态的任务。

主要 Controller：

- [简历 API](src/main/java/interview/pilot/resume/api/ResumeController.java)
- [面试 API](src/main/java/interview/pilot/interview/api/InterviewController.java)
- [面试方向 API](src/main/java/interview/pilot/interview/api/InterviewPresetController.java)
- [语音录制与播报 API](src/main/java/interview/pilot/voice/api/VoiceRecordingController.java)（实时对话走 WebSocket `/ws/voice-interview`，由 `voice/realtime/handler/RealtimeVoiceWebSocketHandler` 处理）
- [知识库 API](src/main/java/interview/pilot/knowledge/api/KnowledgeBaseController.java)
- [异步任务 API](src/main/java/interview/pilot/async/api/AsyncTaskController.java)
- [模型 Provider API](src/main/java/interview/pilot/ai/provider/AiProviderController.java)

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

- 前端组件与自定义 Hook（含实时语音面板、录音、SSE）测试通过，TypeScript 编译和生产构建成功。
- Skill Catalog、中文 Prompt、面试创建和 AI Gateway 等相关单元测试通过。
- 基于 Testcontainers 的面试创建持久化、并发答题和 RabbitMQ 报告处理集成测试通过。
- `bootJar` 构建、`docker compose config` 和 `git diff --check` 通过。

核心业务旅程测试使用 WireMock 支撑的测试 Gateway 替代真实模型，能够在没有 API Key 的情况下验证 Prompt 分类、服务编排、状态流转和持久化：

[InterviewJourneyIT](src/test/java/interview/pilot/e2e/InterviewJourneyIT.java)

该测试不覆盖 HTTP Controller、SSE 传输、RabbitMQ Listener/Dispatcher 和真实 `SpringAiGateway`，这些边界由独立测试覆盖。

项目不对 QPS、延迟、用户量、成本节省或生产使用情况作未经测量的声明。

重点测试：

- [回答并发与幂等](src/test/java/interview/pilot/interview/application/SubmitAnswerConcurrencyIT.java)
- [RabbitMQ 重试与死信](src/test/java/interview/pilot/async/messaging/RabbitRetryIT.java)
- [知识库上传、索引与召回](src/test/java/interview/pilot/knowledge/retrieval/QdrantKnowledgeRetrieverIT.java)
- [简历分析 Listener](src/test/java/interview/pilot/async/resume/ResumeAnalysisListenerIT.java)
- [报告 Listener](src/test/java/interview/pilot/async/report/InterviewReportListenerIT.java)
- [固定流程策略](src/test/java/interview/pilot/interview/domain/FixedInterviewFlowPolicyTest.java)
- [回答服务与 SSE 编排](src/test/java/interview/pilot/interview/application/FixedAnswerServiceTest.java)

## 常见问题

- **没有可用模型：** 为至少一个 Provider 设置非空 API Key 和 `*_ENABLED=true`，并让 `AI_DEFAULT_PROVIDER` 使用相同 ID。Provider Client 在启动时组装，修改后需要重启 App。
- **启动时提示 LLM Lease 无效：** 增大 `LLM_LEASE`，或降低已启用 Provider 的超时时间。Lease 必须大于最长超时时间的两倍。
- **App 一直处于 unhealthy：** 查看 `docker compose logs app mysql redis rabbitmq`。Actuator 会检查基础设施健康状态，凭据错误或依赖不可用都会让 App 保持不健康。
- **简历或报告一直 pending：** 查询任务接口并查看 RabbitMQ 管理页面。失败或进入 `DEAD` 的任务可以在 Redis Claim 释放后，通过 `POST /api/tasks/{taskId}/retry` 重试。
- **SSE 响应看起来被缓冲：** 通过项目自带的 Nginx 访问。它已经为 `/api/` 关闭代理缓冲并延长读写超时；其他反向代理也需要保留这些配置。
- **端口被占用：** 在 `.env` 中修改 `FRONTEND_PORT`、`MYSQL_PORT`、`REDIS_PORT`、`RABBITMQ_PORT` 或 `RABBITMQ_MANAGEMENT_PORT`。Spring Boot 端口在 Compose 中不会公开到宿主机。
- **Docker 拉取镜像失败：** 如果日志出现镜像站 EOF 或超时，这是 Docker 镜像源网络问题，不是项目构建错误。检查 Docker Desktop 的 Registry Mirror 配置后重试。
