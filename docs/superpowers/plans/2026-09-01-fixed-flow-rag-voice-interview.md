# 固定流程、RAG 与 WebSocket 语音面试重构计划

## 1. 目标

将 InterviewPilot 从 Skill/Competency/Plan 驱动的动态面试，重构为面向 Java 后端技术栈的固定流程面试，同时保留必要的局部自适应能力，并接入一套可追溯、可恢复的 WebSocket 语音面试。

目标体验：

```text
预设 JD / 自定义 JD + 简历 + 难度 + RAG
        ↓
面试准备阶段批量生成主问题
        ↓
固定流程逐题进行
        ↓
项目和场景题按需生成轻量追问
        ↓
文字或语音统一进入同一套面试核心
        ↓
RAG 参与出题和结束评分
```

## 2. 已确定的设计决策

### 面试流程

固定主线为：

1. 自我介绍：固定首题，不追问；
2. 基础与原理：Java、Spring、MySQL、Redis、消息队列、并发和分布式基础；
3. 项目 / 实习：优先依据简历项目和 JD 生成；
4. 场景 / 技术取舍：考察故障、约束、指标、降级和演进；
5. 结束并生成报告。

流程推进由 Java 固定状态机负责，不再由 LLM 决定跳转、能力覆盖或结束条件。

### 岗位配置

删除 Skill 作为“面试官角色”的职责。内置 `java-backend` 改为岗位预设 JD，内容只描述岗位范围、技术栈和岗位要求，不包含 persona、competencies、stages、RAG 策略或工具配置。

创建面试支持两种来源：

- 选择内置岗位预设 JD；
- 前端直接提交自定义 JD。

预设 JD 和自定义 JD 在后端统一固化为本场 `jobDescriptionSnapshot`。

### 出题

- 自我介绍题使用固定文本；
- 其他主问题在面试准备阶段批量生成并持久化；
- 主问题生成输入为岗位 JD、简历快照、难度和 RAG 上下文；
- 面试运行期通过题目池选择主问题，不再为每个 `NEXT_TOPIC` 在线调用 LLM；
- 项目题和场景题允许在回答完成后实时生成 1～2 个追问，追问只依据当前问题、用户回答和缺失关注点，不评估回答质量，不改变面试主线；
- 追问生成失败时使用预生成追问或固定模板兜底。

### RAG

RAG 只有两个职责：

1. 出题资料：依据 JD、简历技术词和当前固定面试段落检索技术资料，资料快照绑定到题目；
2. 结束评分证据：报告生成时使用本场题目对应的 RAG 快照和检索结果作为外部技术参考。

RAG 不负责决定流程、不代表候选人证据、不直接改变分数。面试运行期不做阻塞式答案检索；必要的回答事实核验作为报告阶段或异步增强任务执行。

### 语音

采用 `React + Browser Audio API + Spring WebSocket + Qwen ASR + 现有 LLM Gateway + Qwen TTS` 的级联方案：

```text
浏览器录音
  → Spring WebSocket
  → Qwen ASR
  → 最终转写文本
  → 统一面试核心
  → Qwen TTS
  → WebSocket 音频事件
  → 浏览器播放
```

前端不持有模型密钥。WebSocket 只负责音频、字幕、控制和结果事件，业务状态仍由后端面试应用层负责。

## 3. 实施阶段

### 阶段一：固定流程与低延迟文字面试

#### 领域与 API

- 用 `InterviewPreset`、`InterviewPhase`、`QuestionType`、`QuestionCard` 替代 Skill/Competency 的运行时职责；
- 会话快照保存预设或自定义 JD、简历、难度、固定流程和题目池；
- 面试轮次保存 `phase`、`questionType`、问题、回答、RAG 快照和评估结果；
- 删除对外响应中的 `skillId`、`targetCompetency`、`plan`、`TurnDirective` 和动态能力决策字段；
- 保留 `requestId`、回答 Claim、版本号和失败重试语义。

#### 题目准备

- 创建面试后进入 `PREPARING`；
- 通过现有任务表和 RabbitMQ 异步生成主问题池；
- 题目准备成功后进入 `READY`，准备失败进入可重试终态；
- 自我介绍固定题可以直接进入首轮，其他题目准备完成后才允许开始后续阶段；
- 使用批量结构化 LLM 调用生成主问题，避免每轮在线生成；
- 题目池必须持久化，不能只放内存或 Redis。

#### 追问

- 只有项目、实习和场景阶段允许追问；
- 每个主问题创建随机 1～2 次追问额度并持久化；
- 追问 LLM 只返回一个短问题；
- Prompt 必须要求引用用户刚刚提到的技术或事实，例如“你刚刚提到 Redis……”；
- 追问不得修改阶段、问题类型或 JD 主题；
- 追问调用失败、超时或返回非法 JSON 时使用兜底问题，并保证当前轮次可以继续。

#### RAG 与报告

- 按阶段和 JD/简历技术词构造检索请求；
- 每张主问题保存检索快照和来源 ID；
- 结束报告读取本场问答和 RAG 快照，生成阶段评分、技术事实参考、冲突说明和改进建议；
- 报告生成继续使用 RabbitMQ、任务表、重试、死信和 MySQL 最终状态。

### 阶段二：WebSocket 语音面试

#### 会话与协议

- 增加语音会话创建、查询、暂停、恢复和结束接口；
- 增加 `/ws/voice-interview/{sessionId}`；
- 客户端事件至少包括 `audio`、`start`、`pause`、`resume`、`end`；
- 服务端事件至少包括 `ready`、`subtitle`、`question`、`audio`、`processing`、`error`、`completed`；
- WebSocket 建连和每个控制事件都校验 JWT、会话归属和当前状态。

#### 语音处理链路

- 使用 Qwen 实时 ASR 接收音频并返回 partial/final 转写；
- 只在 final transcript 上创建一次回答 `requestId`；
- 对连续 final 片段做合并和防抖，防止重复提交；
- 用户回答提交后调用阶段一的统一回答处理；
- 下一题或追问生成后调用 Qwen TTS；
- TTS 音频通过 WebSocket 返回，并支持 AI 播放期间的回声保护；
- 第一版不保存原始音频，只保存转写文本、音频时长、输入方式和必要的 ASR 元数据。

#### 真实语音难题

必须实现并可观测：

- ASR partial/final 重复和乱序；
- 用户沉默和服务端 VAD；
- AI 播放声音被 ASR 重新识别的回声问题；
- WebSocket 断线后的会话恢复；
- 用户重复点击结束或提交；
- TTS/LLM 超时后的可恢复状态；
- 音频处理不能占用数据库事务；
- 单会话处理串行、不同会话可并发，并受 Redis/线程池限流。

## 4. 异步、缓存和状态设计

### MySQL

MySQL 是业务事实来源，保存：

- 面试会话和固定流程状态；
- 岗位 JD、简历和题目池快照；
- 面试轮次、转写文本、评估和 RAG 快照；
- 语音会话元数据；
- 题目准备和最终报告任务状态。

### Redis

Redis 只保存短期协调状态：

- 语音会话连接和心跳信息；
- ASR/TTS 短期上下文；
- 题目准备锁和回答 Claim；
- 限流、并发租约和幂等辅助信息。

Redis 丢失后，系统必须能够从 MySQL 恢复面试事实。

### RabbitMQ

新增或扩展以下异步任务类型：

- `INTERVIEW_QUESTION_PREPARATION`：批量主问题生成；
- `INTERVIEW_EVALUATION`：结束报告生成；
- 后续可增加 `INTERVIEW_RAG_VERIFICATION`：回答事实核验。

任务必须具备幂等业务键、发布确认、重试、死信、状态围栏和人工重试能力。

### 状态流转

```text
CREATED → PREPARING → READY → INTERVIEWING
                              ↓
                         EVALUATING → COMPLETED
                              ↓
                            FAILED
```

语音连接状态不直接等同于面试状态。断开只影响传输连接，不能自动结束面试；恢复时以 MySQL 当前轮次和状态为准。

## 5. 测试与验收标准

### 文字面试

- 预设 JD 和自定义 JD 都能创建面试；
- 首题固定且不触发追问；
- 主问题在准备阶段批量生成并持久化；
- `NEXT_TOPIC` 不触发在线问题生成；
- 项目和场景题追问次数只在 1～2 次范围内；
- 追问围绕最新回答，不能跳出当前主题；
- 题目生成失败可重试，不能生成半成品题库；
- RAG 失败时仍能出题，报告明确标记资料不可用；
- 重复提交、并发提交、断开 SSE 和失败重试保持现有幂等语义。

### 语音面试

- WebSocket 建连鉴权和越权访问测试；
- partial/final ASR 不重复创建回答；
- 一次回答只产生一个 `requestId`；
- AI 播放期间不会把自己的声音当成用户回答；
- LLM/TTS 超时后会话可继续或进入明确可恢复状态；
- WebSocket 断线后重新连接能恢复当前问题和已保存回答；
- 文字面试和语音面试产生相同的面试状态和评估结果；
- 限制单会话串行处理，多个会话可以并发；
- 前端能够显示字幕、处理状态、播放下一题和错误恢复提示。

### 工程验证

```text
.\gradlew.bat test
cd frontend
pnpm exec vitest run
pnpm build
cd ..
docker compose config
git diff --check
```

## 6. 明确不做的事情

- 不引入 WebRTC、LiveKit 或独立媒体服务器；
- 不让 LLM 决定面试阶段和业务状态；
- 不让 RAG 直接判断候选人是否具备能力；
- 不在每一轮为主问题重复调用 LLM；
- 不在第一版保存原始音频；
- 不恢复 `Skill + Competency + Stage + Plan` 的旧运行时模型；
- 不为了兼容旧接口而继续向新的前端响应暴露旧字段。

