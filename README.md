# InterviewPilot

InterviewPilot 是一个基于 Spring Boot 与 React 的企业面试管理与 AI 辅助评估系统，同时保留个人模拟面试能力。企业可以发布岗位、接收简历投递，结合 JD、简历与企业知识库准备面试题，人工确认后批量下发邀请；候选人安排日程并完成面试，企业结合回答证据和 AI 报告进行人工评审，再独立发布公开反馈。

项目采用单体分模块架构，围绕权限隔离、材料快照、异步任务、并发作答和可靠通知实现业务闭环。AI 负责分析、出题与辅助评估；题目下发和反馈发布由企业人员确认。

## 业务流程与入口

```mermaid
flowchart LR
    A[企业发布岗位与 JD] --> B[候选人投递简历]
    B --> C[岗位证据分析与面试方案]
    C --> D[批次逐人准备题卡]
    D --> E[企业审核并下发邀请]
    E --> F[候选人安排日程与参加面试]
    F --> G[AI 报告与人工评审]
    G --> H[预览并发布公开反馈]
    H --> I[下一轮或结束流程]
```

| 使用者 | 页面入口 | 主要功能 |
|---|---|---|
| 企业管理员 / 招聘人员 | `/enterprise` | 岗位、投递、面试方案、批次、评审、通知记录及团队权限 |
| 被指派的面试官 | `/enterprise` → 面试与评审 | 查看授权面试的回答证据、保存草稿和提交评审 |
| 求职者 | `/jobs`、`/candidate/applications` | 浏览开放岗位、投递简历、查看投递状态 |
| 求职者 | `/candidate/invitations` | 安排面试、下载 ICS 日程、开始或恢复作答、查看公开反馈 |
| 登录用户 | `/notifications` | 站内通知与已读状态 |
| 个人练习用户 | `/resumes`、`/interviews/new` | 简历分析、模拟面试及个人报告 |

企业招聘与个人练习使用不同的执行规则和报告可见范围。企业面试按确认后的冻结题卡执行，不追加动态追问；候选人不能直接查看企业内部 AI 报告，只能查看企业明确发布的反馈。

## 整体架构

系统自上而下分为接入层、安全与流量治理、面试核心链路、异步任务总线和存储/外部依赖五层；耗时的大模型调用与业务状态推进解耦，一致性与可靠性作为横切设计贯穿各层。

```mermaid
flowchart TB
    subgraph L1["接入层"]
        direction LR
        WEB["Web 面试端 · REST/SSE"]
        HIRING["企业工作台 / 候选人门户"]
        VOICE["语音面试 · WebSocket / MediaRecorder"]
    end

    subgraph L2["安全与流量治理"]
        direction LR
        JWT["JWT 认证"]
        OWN["三层数据归属校验"]
        LUA["Redis Lua 多维限流"]
        LEASE["ZSET 并发租约"]
    end

    subgraph L3["面试核心链路"]
        direction LR
        KB["① 知识库构建"] --> GEN["② 骨架→逐题检索→Rubric 冻结"] --> FLOW["③ 状态机推进"] --> EVAL["④ 答案评估"] --> RPT["⑤ Barrier 聚合报告"]
    end

    subgraph HR["企业招聘业务"]
        direction LR
        JOB["岗位 / 投递快照"] --> BATCH["方案 / 批次 / 人工确认"] --> INVITE["邀请 / 日程 / 冻结执行"] --> REVIEW["人工评审 / 公开反馈"]
        NOTICE["通知台账 / 租约投递 / SMTP"]
    end

    subgraph L4["异步任务总线"]
        direction LR
        TASK["MySQL 任务表 Outbox"] --> SCAN["定时扫表"] --> MQ["RabbitMQ 重试/DLQ"] --> HDL["Handler 幂等领权"]
    end

    subgraph L5["存储与外部依赖"]
        direction LR
        DB[("MySQL")]
        RDS[("Redis")]
        VDB[("Qdrant")]
        FS[("私有存储")]
        LLM["Spring AI / LLM"]
        TTS["DashScope ASR/TTS"]
    end

    L1 --> L2 --> L3
    L2 --> HR
    HR --> L3
    HR --> L4
    HR --> L5
    L3 <-->|"Outbox 登记 / 异步回写"| L4
    L3 --> L5
    L4 --> L5

    classDef core fill:#EAF1FE,stroke:#3B6FD4,stroke-width:1.5px,color:#1A1B1C;
    class GEN,FLOW,EVAL,RPT core;
```

## 项目展示

企业工作台、面试方案、面试批次及 AI／人工评审截图更新于 2026-09-15，来自当前前端的本地运行页面，使用模拟接口与合成数据展示界面。开放岗位、候选人反馈及通知截图保留于 2026-09-09 的本地演示。操作步骤见[评审与通知指南](docs/hiring-review-notification-guide.md)。

### 开放岗位

岗位以卡片呈现，集中展示公司、岗位名称、地点与工作类型；通过明确的「查看岗位」按钮进入详情和投递流程。

![开放岗位：岗位卡片、信息分区与查看岗位按钮](assets/images/hiring-jobs.png)

### 企业招聘工作台

左侧选择岗位，右侧按「投递管理」「面试方案」「面试批次」切换操作；公司导航与岗位工作区分层展示。

![企业招聘工作台：岗位管理与业务导航](assets/images/hiring-enterprise.png)

### 面试方案编辑

方案列表、编辑和发布预览分开展示。编辑表单按基本设置、企业知识库、面试阶段、公共题与评分标准分区，发布历史收纳在对应方案下。

![面试方案编辑：基本设置、知识库、面试阶段与公共题分区](assets/images/hiring-scheme.png)

### 面试批次与逐人准备

按已发布方案为候选人准备题目，查看准备与确认数量，逐人预览题卡。企业确认后下发邀请，并查看候选人的日程状态。

![面试批次：三名候选人的题目准备、确认与邀请状态](assets/images/hiring-campaign.png)

### AI 辅助评估

企业查看面试总结、参考分数、优势与待核实问题，并结合逐题回答证据开展评审。AI 结果作为人工判断的参考。

![AI 评估参考：面试总结、优势与待核实问题](assets/images/hiring-ai-review.png)

### 人工评审

评审人员按维度填写评价、引用回答证据并记录内部评语。草稿可继续编辑，正式提交保留历史修订；候选人反馈在独立区域预览和发布。

![人工评审：评价维度、回答证据引用与内部评语](assets/images/hiring-human-review.png)

### 候选人日程与公开反馈

候选人在面试邀请中查看安排、下载日历和回看作答记录。企业发布反馈后，这里展示公开内容、下一步决定和发布版本。

![候选人面试邀请：日历入口与企业已发布反馈](assets/images/hiring-candidate-feedback.png)

### 通知与邮件记录

企业查看日程更新及面试提醒的投递状态与尝试次数。本地截图中的「邮件服务商已接收」来自测试 SMTP 接收器，不代表外部邮箱已送达。

![通知投递记录：待发送提醒与邮件接收状态](assets/images/hiring-notifications.png)

<details>
<summary>展开个人练习与通用能力截图</summary>

以下为此前版本的个人练习界面截图。

#### 简历分析

对简历进行结构化分析，展示评分与内容、技能、项目经历方面的改进建议。

![简历分析：评分与优化建议](assets/images/resume-analysis.png)

#### 创建个人模拟面试

选择简历、面试方向、岗位要求、难度、模型和知识库，创建结构化练习。

![开始面试：配置岗位、模型和知识库](assets/images/interview-create.png)

#### 知识库

上传文档、查看索引状态和分段数量，并支持重建索引。

![知识库：文档上传与索引管理](assets/images/knowledge-base.png)

#### 实时语音

流式转写实时回显，用户手动确认后提交回答；支持语音播报和录音转写兜底。

![语音面试：实时对话与手动确认提交](assets/images/interview-voice-realtime.png)

#### 个人面试报告

练习结束后查看综合评分、能力维度、优势与改进方向。企业内部报告采用独立的可见范围。

![个人面试报告：综合评分与能力评估](assets/images/interview-report.png)

</details>

## 功能特性

### 企业招聘

- 企业、成员角色与岗位授权；企业知识库和业务数据按归属隔离。
- 岗位发布版本、简历投递快照、撤回与显式重新投递；历史材料不随后续修改改变。
- 基于 JD 和简历的异步岗位证据分析，面试方案发布后保留不可变版本。
- 面试批次逐人准备公共题与定制题，保留评分依据和知识引用，支持人工调整、逐人确认及部分下发。
- 邀请确认、拒绝、取消、窗口内安排和改期，支持 ICS 日程下载；变更后需重新导入日历。
- 邀请绑定唯一面试会话，按冻结题卡作答，支持断线恢复、截止时间与超时收尾。
- 人工评审指派、不完整草稿、不可变提交修订及历史查询；公开反馈独立预览和版本化发布。
- 站内通知、SMTP 邮件及默认提前 24 小时 / 1 小时提醒；支持过时提醒跳过、发送记录和有限重试。

### 个人练习与通用能力

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

知识库/RAG 需要显式开启 `KNOWLEDGE_ENABLED=true` 并配置 Embedding API Key；混合检索使用 `KNOWLEDGE_RETRIEVAL=hybrid`。语音面试通过 `VOICE_ENABLED=true` 开启，实时对话与录音转写均要求候选人确认答案。

当前企业招聘主流程已接通，完整 V1 仍有独立题库、超出窗口的改期审批、配额及运维压测等待办；计费和通用报告导出未实现。范围与进度见[实施方案](docs/enterprise-interview-v1-plan.md)和[实施进度](docs/enterprise-interview-v1-progress.md)。

## 技术栈

- 后端：Java 21、Spring Boot 4、Spring MVC、WebSocket、Spring Data JPA、Hibernate、Flyway、Spring AI
- 数据库：MySQL 8.4
- 消息队列：RabbitMQ 4
- 邮件：Spring Boot Mail、SMTP，MySQL 通知台账与发送租约
- 向量库：Qdrant
- 语音：DashScope 实时 ASR/TTS（Qwen3-Realtime，WebSocket），MediaRecorder 非实时转写兜底
- 限流与模型并发配额：Redis 7.4、Redisson；任务执行协调：MySQL 租约
- 前端：React 18、TypeScript、Vite、Vitest
- 网关：Nginx
- 测试：JUnit 5、Testcontainers、WireMock、Testing Library
- 部署：Docker、Docker Compose

## 架构与后端设计

### MySQL 作为业务事实来源

MySQL 持久化简历、分析结果、岗位要求、Skill 快照、面试计划、会话、轮次、回答尝试、报告和异步任务状态，同时保存企业、投递、批次邀请、评审修订、公开反馈和通知台账。任务执行身份由 MySQL 租约管理，RabbitMQ 负责投递，Redis 负责限流与模型并发配额。

Skill、Provider、Model 和 InterviewPlan 都会在创建面试时生成不可变快照。即使之后修改默认模型或 Skill 文件，已经开始的面试仍使用创建时的版本。

### 个人练习流程与受控的 AI 输出

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

企业面试的新作答请求还必须携带 `expectedTurnNo` 和 `sessionVersion`，并纳入幂等指纹，防止旧标签页把回答写入新题。实时语音绑定实际下发的题目版本，在处理队列中再次校验识别代次，丢弃重复确认及迟到转写。

企业写操作按统一顺序加锁，评审草稿和反馈发布分别校验版本；同一版本并发发布只允许一个请求成功，旧轮次不能覆盖已进入后续轮次的流程结果。

在线答题的领取、完成和失败清理统一按「会话 → 轮次 → 回答尝试」访问状态，短事务内锁定会话和轮次；失败清理也校验 `requestId`，防止旧工作线程误伤新回答。模型调用仍在事务外执行。

进程退出后遗留的 `PROCESSING` 回答由定时任务恢复：超过 `InterviewProcessingSla` 的 SSE 时限后，基于已保存的答案和冻结题卡，用备用追问或下一道主问题完成流转，并在同一事务保存可重放结果。恢复不调用模型；旧题卡缺少备用题时保留答案并标记失败，允许用户重试。企业面试超过截止时间时仍交由原有截止处理流程收尾。扫描每批最多 50 条，以 ID 游标推进并逐条隔离失败；默认启动 1 分钟后执行，每轮间隔 30 秒，可用 `app.interview.answer-recovery.initial-delay` / `interval` 调整。

### 人工评审与可靠通知

企业内部回答、AI 评估、冻结评分依据及知识证据仅向授权人员开放。面试官需要逐场指派；管理员或授权招聘人员才能选择已提交的评审修订，另行填写并发布候选人反馈。评审历史与公开反馈分别存储，候选人接口不返回内部评语和评分依据。

通知与业务变更在同一事务写入 MySQL。邮件在独立后台线程中发送，以唯一事件键去重，并通过领取令牌和租约协调多实例。旧日程或已结束邀请的提醒会被跳过；明确连接失败有限重试，超时或发送中断标记为 `UNKNOWN`，不自动重发。SMTP 接受不等于邮件已被阅读，站内已读独立记录。

### 事务与模型调用边界

耗时的大模型调用不会占用数据库事务：

1. 短事务校验状态并领取处理权。
2. 在事务外调用模型并校验结构化结果。
3. 新短事务重新检查版本和所有权，再持久化结果并推进状态。

这种方式减少了数据库锁占用，同时允许通过版本围栏丢弃已经失去所有权的迟到结果。

### RabbitMQ 可靠异步任务

简历分析、知识库索引和面试报告通过数据库任务表与 RabbitMQ 协作处理。任务发布使用 Publisher Confirm，并配置分级延迟重试队列和 DLQ。由于数据库提交和消息发布无法形成一个本地原子事务，系统采用任务表扫描、周期重发和消费者幂等实现至少一次投递。

消费端区分业务失败与协调等待：模型或处理失败使用 5/30/120 秒重试；数据库领权不可用、租约被占用时进入 30 秒延迟队列，保留原重试次数。延迟消息必须收到发布确认且未被退回，本次消费才正常返回，避免提前确认后丢失崩溃任务的后续执行机会。每次领权递增 `attemptCount`；结果和失败回写同时校验 token、执行代次与 `executionEpoch`。异常携带已提交的执行身份，未取得执行权的异常只能延迟等待，不能终结其他执行者的任务。

#### 简历分析：MySQL 执行租约试点

`RESUME_ANALYSIS` 的消费领权与人工重试已不再调用 Redis Claim。任务行新增 `execution_token` 和 `execution_lease_until`，由 MySQL 同时管理业务状态与执行身份：

1. **短事务领权：** 条件 UPDATE 校验任务身份、`executionEpoch`、可执行状态及租约是否空闲或过期；成功后生成独立 token、递增 `attemptCount`，并在同一事务把简历改为 `ANALYZING`。租约使用数据库时间，避免不同应用实例的时钟偏差。
2. **事务外执行：** 提交领权事务后调用模型。并发重复消息领权失败时进入 30 秒延迟队列，不占用业务重试次数。
3. **短事务回写：** 先锁定任务行，再核对 token、执行代次与 epoch；成功和失败都必须属于当前执行者，才允许更新任务及简历。终态在同一事务清除租约。
4. **重试与恢复：** 可重试失败先保留租约，确认延迟消息发布成功后再按原 token 释放；死信也由原执行身份落库。进程退出后依靠 RabbitMQ 未确认消息重投及延迟投递，在租约过期后接管。人工重试在数据库事务中重置业务状态、清除租约并递增 epoch，旧消息不能执行新一轮任务。

租约默认 `2m`，可通过 `app.async.resume-analysis.lease-duration` 调整，范围为 1 秒到 1 小时的整数秒。租约到期仅表示允许其他执行者接管；没有发生接管时，原执行者仍可提交。当前不自动续租，慢调用超过租期可能与接管者重复调用模型，但旧 token 不能覆盖新执行者的数据，因此不承诺模型调用严格只执行一次。

#### 题卡准备：复用数据库执行租约

`INTERVIEW_QUESTION_PREPARATION` 的消费领权与人工重试也已迁移到 MySQL。两条链路共用 `AsyncTaskRepository.claimExecution/releaseExecution` 的条件更新，以及任务实体的 `ownsExecution/clearExecutionLease`，按任务类型和业务键隔离执行身份。

- 出题、逐题 RAG 检索和评分要点生成均在数据库事务外执行；领权失败返回 `BUSY`，通过确认发布的延迟消息等待。
- 写入前锁定任务行，核对 token、执行代次、epoch 和会话归属；自我介绍与全部题卡、会话 `READY`、任务 `COMPLETED` 在同一事务提交。中途抛错时整批回滚，不留下半套题卡。
- 旧执行的成功、无效输出及运行时失败均受身份校验约束，不能删除接管者的题卡或把新任务标记为失败。可重试失败及乐观锁冲突在确认消息发布后才释放当前租约；发布失败则保留租约并让异常返回消息容器。
- 租约默认 `11m`，可通过 `app.async.question-preparation.lease-duration` 配置，仍采用前述不续租的过期接管语义。人工重试在一个事务中恢复会话、清除租约并递增 epoch。

#### 回答评估：执行身份与回答绑定

`ANSWER_EVALUATION` 也已复用上述 MySQL 执行租约，消费领权不再依赖 Redis Claim。模型评估在事务外执行，评分与任务完成在同一短事务提交。

- 领权通过条件 UPDATE 完成；重复消费遇到有效租约时延迟等待，不消耗业务重试次数。租约默认 `2m`，通过 `app.async.answer-evaluation.lease-duration` 配置，范围为 1 秒到 1 小时的整数秒，采用不续租的过期接管语义。
- 成功、失败回写先锁任务、再锁回答，校验 token、执行代次、epoch，以及回答的 `requestId` 和状态。旧执行返回的评分或异常不能覆盖接管者，也不能误写到另一份回答上。
- 重试消息确认发布后才释放原 token 的租约；达到重试上限时仅把评估标记为 `FAILED`、任务标记为 `DEAD`，保留已提交回答和面试流程状态。回答评估仍不支持通用人工重试接口。
- 报告保持原有有界等待：评估未完成时先延迟等待，等待次数耗尽后使用题目与原始回答生成报告；已失败的评估直接降级。晚到评分不会重写已保存的报告快照。

#### 其余消费者与辅助协调

报告生成、知识库索引、语音转写和语音合成也采用同一套数据库执行租约，所有 RabbitMQ 消费者均不再使用 Redis Claim。企业批次任务与通知投递保留已有的业务表租约和恢复机制。

| 链路 | 配置项 | 默认租期 | 回写保护 |
| --- | --- | --- | --- |
| 报告生成 | `app.async.interview-report.lease-duration` | `11m` | 报告、会话完成和任务完成一起提交；保留评估有界等待 |
| 知识库索引 | `app.async.knowledge-index.lease-duration` | `2m` | 校验执行身份及文档版本，再提交解析元数据、READY 和任务完成 |
| 语音转写 | `app.async.voice-transcription.lease-duration` | `2m` | 校验执行身份、录音 epoch 和 TRANSCRIBING 状态 |
| 语音合成 | `app.async.voice-synthesis.lease-duration` | `2m` | 校验执行身份、语音 epoch 和题目文本；每次执行写独立音频路径 |

上述租期均支持 1 秒到 1 小时的整数秒，不自动续租。知识库分块和向量按文档版本及确定性 ID 幂等写入，外部向量库与 MySQL 不构成分布式事务。语音合成的旧执行只能清理自己未被引用的文件，不能覆盖或删除新执行的音频。

人工重试在同一数据库事务内校验失败状态、恢复业务行、清空租约并递增 epoch；录音和题目语音仍通过各自的业务重试入口操作。在线答题移除了额外的 Redis 准入锁，直接使用已有会话/轮次行锁、请求指纹和唯一约束。语音清理使用 `background_run_lease` 的数据库租约，处理卡住任务时必须再次确认任务足够陈旧且执行租约可接管。

升级时先停止并等待所有旧版应用实例和 Worker 退出，再执行 Flyway 至 V37、启动新版；不要混跑旧 Redis 消费者与新数据库租约消费者。V36 提供任务执行字段，V37 新增清理运行租约表与音频引用查询索引。保留现有 Redis 服务供限流和模型网关使用。

### 知识库与 RAG

知识库文档上传后会先进入 `PROCESSING`，后台任务负责解析文件、切分文本，先将分块写入 MySQL 的 `knowledge_chunk` 表，再向量化写入 Qdrant，并在成功后标记为 `READY`。两路使用相同的确定性分块 ID，失败重试可以幂等重放。文档列表会返回 `PENDING`、`PROCESSING`、`READY`、`FAILED`、`DELETING` 等可见状态，前端会对索引中的文档轮询刷新。

检索默认使用 `KNOWLEDGE_RETRIEVAL=vector`，保持原有单路向量检索行为。设为 `hybrid` 后，增加 MySQL 原生 ngram FULLTEXT 词法召回：优先使用题目检索关键词，没有有效关键词时使用查询文本；两路结果按分块 ID 通过 RRF（`k=60`）融合，再统一做近似去重、Top-K 和字符预算裁剪。不引入 ES、重排模型或新中间件。

混合检索只比较各路候选排名，不直接比较 FULLTEXT 与向量原始分数。返回的融合分数按理论最大值归一到 `[0,1]`，仅用于排序；向量相似度阈值仍只约束向量候选，不过滤词法候选或融合结果。任一路失败时仍可使用另一路的命中；没有命中且存在失败时返回 `UNAVAILABLE`。

升级后，已有文档不会自动生成 MySQL 分块镜像。需要词法召回的历史文档应在知识库页面执行「重建索引」，完成后创建新面试使用新版本；已有面试继续使用冻结的文档版本，未有镜像的旧版本仍可走向量召回。切回 `vector` 只需修改配置并重启应用，分块双写会继续保留。

创建面试时如果选择知识库，系统只会把当前 `READY` 文档纳入召回范围；每轮生成题目或评估答案前都会按用户、知识库、文档和索引版本过滤召回结果，避免跨用户、跨知识库或旧版本内容进入上下文。删除文档会清理文件和向量，并把旧索引消息视为终态，避免晚到消息反复重试。

### Redis 的职责

Redis 承担两类短期协调职责：

- API 固定窗口限流。
- 全局大模型并发租约。

Redis 不保存面试业务事实，也不再决定任务执行权。Redis 故障仍可能影响限流和真实模型调用，数据库租约迁移不代表整个应用已能脱离 Redis 运行。

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

先按下方[配置说明](#配置说明)创建 `.env`，设置数据库凭据、JWT 密钥和至少一个模型 Provider，再启动：

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

前端默认访问 `http://localhost:5173`，Vite 会将 `/api` 代理到 `localhost:8080`。Spring Boot 会读取项目根目录中可选的 `.env` 文件。本地启用知识库时，还需启动并向宿主机映射 Qdrant 的 gRPC 端口（默认 `6334`）；Compose 中的 Qdrant 默认仅在容器网络内开放。

## 配置说明

复制环境变量示例：

```bash
cp .env.example .env
```

Windows PowerShell 可以使用：

```powershell
Copy-Item .env.example .env
```

修改 `.env` 中的数据库、RabbitMQ 密码，生成并填写 `JWT_HMAC_SECRET`，并至少启用一个模型 Provider。不要提交 `.env`，也不要覆盖已有配置。

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

### 企业面试与邮件配置

企业招聘接口随应用启用；邮件通道默认关闭，站内通知仍可使用。数据库通过 Flyway 自动迁移，当前包含 V33 的评审与通知表；升级现有数据库应追加迁移，不修改已应用的脚本。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `HIRING_MAX_BATCH_MEMBERS` | `200` | 单个批次成员上限 |
| `HIRING_EVIDENCE_RETENTION_DAYS` | `90` | 硬截止后冻结知识证据保留天数 |
| `HIRING_MAIL_ENABLED` | `false` | 是否启用邮件发送 |
| `HIRING_MAIL_FROM` | `interview-pilot@example.test` | 发件地址，实际发送时按 SMTP 服务配置 |
| `HIRING_PUBLIC_BASE_URL` | `http://localhost:5173` | 邮件中的页面入口，需与访问地址一致 |
| `HIRING_REMINDER_HOURS` | `24,1` | 面试开始前的提醒时间，单位小时 |
| `SMTP_HOST` / `SMTP_PORT` | `localhost` / `1025` | SMTP 服务器地址与端口 |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | 空 | SMTP 认证凭据 |
| `SMTP_AUTH` / `SMTP_STARTTLS` | `false` | 认证与 STARTTLS 开关，按服务商要求设置 |

本地联调可先运行 `node scripts/local-mail-sink.mjs`，再将 `HIRING_MAIL_ENABLED=true` 后启动后端。接收器仅监听 `127.0.0.1:1025`，仅接受 `.test` 测试邮箱，邮件写入 `.codex-local/mail`，不向外转发。

上述变量可由本地 `bootRun` 读取 `.env`。当前 Compose 的 `app.environment` 尚未枚举 `HIRING_*` 和 `SMTP_*`；容器部署启用邮件时，需要在 Compose 配置或覆盖文件中显式传入这些变量，并使用容器可访问的 SMTP 地址。仅修改宿主机 `.env` 不会自动传入这些新增变量。详细步骤与投递状态说明见[评审与通知指南](docs/hiring-review-notification-guide.md)。

## 语音面试（Voice Interview）

语音面试提供两种模式，均由 `VOICE_ENABLED` 总开关控制，关闭时文字面试完全不受影响。

### 实时对话模式（默认）

开启语音后默认使用实时对话：浏览器与后端保持**一条 WebSocket**（`/ws/voice-interview/{sessionId}`，握手通过 `?token=` 携带 JWT 并校验会话归属）。麦克风采集 16kHz/16bit 单声道 PCM，以 100ms 一帧持续上行；后端转发 DashScope 实时 ASR（`qwen3-asr-flash-realtime`，服务端 VAD 静音 800ms 仅用于自动断句），partial/final 文本作为实时字幕持续回显并在服务端累积，**默认不会自动提交**——候选人确认内容后点「提交回答」（前端发送 `submit` 控制帧，并可携带最后尚未落定的半句），后端才以 `VOICE_REALTIME` 模式交给同一套回合引擎（claim/process），再由实时 TTS（`qwen3-tts-flash-realtime`）整段合成 24kHz PCM、封 WAV 经同一连接回推播放。个人练习如需「停顿后自动发送」，可设 `app.voice.realtime.conversation.auto-submit=true`；企业面试始终要求手动确认。

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
| `VOICE_CLEANUP_CLAIM_TTL` | `PT30M` | 清理运行 MySQL 租约时长 |

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

企业邀请创建的面试在第 5 步还需提交 `expectedTurnNo` 和 `sessionVersion`。实时语音控制消息携带实际收到的题目 `turnNo`；企业面试始终要求手动确认转写。

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

### 企业招聘流程

1. 企业在 `/enterprise` 创建岗位并发布；求职者在 `/jobs` 选择岗位，上传或选择简历后投递。
2. 企业查看冻结的投递材料，发起岗位证据分析，编辑并发布面试方案。
3. 在岗位下创建面试批次，逐人等待题卡准备完成，查看题目、评分依据和来源，人工确认后下发邀请。
4. 求职者在 `/candidate/invitations` 安排时间、下载 ICS，在允许的时间范围内开始或恢复面试。
5. 面试结束生成内部 AI 报告，企业在“面试与评审”中指派评审、保存草稿并提交评审修订。
6. 授权人员单独填写、预览并发布反馈；求职者通过通知中心或邀请详情查看公开内容。进入下一轮时，由企业创建新的面试批次。

主要接口按企业及邀请归属鉴权，入口代码：

- [企业与团队](src/main/java/interview/pilot/recruitment/api/OrganizationController.java)
- [岗位与投递管理](src/main/java/interview/pilot/recruitment/api/RecruitmentController.java)
- [公开岗位与候选人投递](src/main/java/interview/pilot/recruitment/api/CandidateRecruitmentController.java)
- [岗位分析与面试方案](src/main/java/interview/pilot/recruitment/api/HiringAssessmentController.java)
- [批次准备与下发](src/main/java/interview/pilot/recruitment/api/HiringCampaignController.java)
- [邀请、日程与执行](src/main/java/interview/pilot/recruitment/api/HiringInvitationController.java)
- [人工评审与反馈](src/main/java/interview/pilot/recruitment/api/HiringReviewController.java)
- [通知与邮件重试](src/main/java/interview/pilot/recruitment/api/HiringNotificationController.java)

### 个人练习流程

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
./gradlew test --tests interview.pilot.recruitment.HiringCampaignIT
```

Windows PowerShell：

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests interview.pilot.recruitment.HiringCampaignIT
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

全部消费领权迁移回归（2026-10-03）：

- 完整后端回归：159 个套件、893 项，883 通过、10 跳过，0 失败 / 错误。
- 报告、知识库索引、ASR、TTS 的真实 MySQL 测试验证同 epoch 下的租约占用、过期接管、错误 token，以及旧成功和旧失败隔离；报告另外验证无效输出隔离及报告/会话/任务提交失败一起回滚。
- TTS 通过读取真实存储文件验证旧执行无法覆盖新音频；清理测试覆盖历史文件引用与失效任务恢复。知识库验证新文档版本拒绝旧解析元数据。
- 清理运行租约通过 12 个线程同时竞争及旧 token 操作测试。消费者重试测试验证发布确认先于租约释放、发布失败不释放、死信落库失败不吞异常。
- 迁移专项的报告/回答评估、知识库与语音消费测试不启动 Redis；真实模型使用替身。本轮未进行容量压测或真实模型质量评测。

回答评估数据库租约迁移回归（2026-10-03）：

- 82 项定向测试全部通过；其中回答评估租约的 16 项集成测试使用真实 MySQL、RabbitMQ 和模型替身，不启动 Redis。
- 验证并发领权只启动一个模型调用，租约过期后旧成功、旧失败、错误 token 和已替换的回答 `requestId` 均不能影响当前评分。
- 通过数据库触发器注入提交失败，验证评分与任务终态一起回滚；验证真实延迟重试、死信落库，并模拟进程退出后的租约过期接管，评估失败不修改已提交回答。
- 验证报告有界等待、失败或等待耗尽时使用原始回答降级，以及晚到评分不会重写报告。
- 另运行 593 项非容器测试；与定向测试去重后，本轮共覆盖 117 个套件、631 项，621 通过、10 跳过，0 失败。未重复执行全部集成套件，也未进行容量压测或真实模型质量评测。

题卡准备数据库租约迁移回归（2026-10-03）：

- 85 项定向测试全部通过，覆盖题卡与简历两条链路、人工重试及消息延迟处理；其中题卡准备的 11 项集成测试使用真实 MySQL、RabbitMQ 和模型替身，不启动 Redis。
- 验证 6 个线程同时抢占仅调用一次生成流程；租约过期后新执行者接管，旧成功、旧无效输出、旧运行时失败以及错误 token 的释放操作均不能影响新执行者。
- 验证写入中途异常使整批题卡回滚，延迟重试完成后只保存一套题卡；死信任务可在 Redis Claim 不可用时人工恢复，旧 epoch 消息不能启动新一轮任务。
- 另运行 587 项非容器测试及 9 项发布补偿、权限、数据库启动测试；与定向测试去重后，本轮共覆盖 120 个套件、640 项，630 通过、10 跳过，0 失败。未重复执行全部集成套件，也未做容量压测或真实模型质量评测。

简历分析数据库租约试点回归（2026-10-03）：

- 完整后端回归执行 157 个套件、842 项；修复 4 个旧迁移测试中写死的脚本数量断言并定向复测后，汇总为 832 通过、10 跳过、0 未解决失败。
- 真实 MySQL 验证 6 个线程同时争抢同一任务时只有一个执行者；过期接管后，旧执行的成功、无效输出和运行时失败都不能覆盖新状态。
- 简历消费集成测试只启动 MySQL 和 RabbitMQ，使用模型替身，并让 Redis Claim 调用直接抛错；验证正常消费、延迟重试、死信和人工恢复均不调用 Redis Claim。
- 升级测试验证存量任务的新租约字段默认为空；本轮未进行容量压测或真实模型调用测试。

后端核心链路专项回归（2026-10-03）：

- 使用真实 MySQL 8.4、RabbitMQ 和 Redis 的 Testcontainers 环境，12 个集成测试套件、113 项全部通过，0 失败 / 跳过；模型调用使用替身。
- 验证两个恢复线程只推进一次回答、已保存答案与重放结果一致、旧线程不能破坏恢复结果，以及租约到期后延迟消息可以继续执行。
- 验证旧出题执行在新代次已提交但任务仍为 `PUBLISHED` 时不能写入题卡；评估失败按实际执行代次落库；V35 恢复索引已按预期创建。
- 覆盖简历、知识库、语音消费，以及企业任务租约、邀请失效、截止收尾、评审权限和通知；修正了一项遗漏投递操作的通知测试。
- 本次是可靠性与一致性验证，未做容量压测或真实模型质量评测。

最近一次完整回归（2026-09-09）：

- 后端 149 个测试套件、806 项：796 通过、10 跳过，0 失败 / 错误。
- 前端 19 个测试文件、156 项全部通过，TypeScript 编译和生产构建通过。
- 本地实际走通岗位投递、批次确认下发、候选人作答、真实模型报告、人工评审、公开反馈和测试 SMTP 接收。候选人访问企业内部报告仍返回 403。
- 邮件仅在本地接收器验证，未发送外部邮件；本轮语音并发由自动化模拟验证，未额外调用真实 ASR。

其他已有验证：

- 前端组件与自定义 Hook（含实时语音面板、录音、SSE）测试通过，TypeScript 编译和生产构建成功。
- Skill Catalog、中文 Prompt、面试创建和 AI Gateway 等相关单元测试通过。
- 基于 Testcontainers 的面试创建持久化、并发答题和 RabbitMQ 报告处理集成测试通过。
- `bootJar` 构建、`docker compose config` 和 `git diff --check` 通过。

自动化测试使用模型替身或 WireMock 验证服务编排与外部调用边界；企业招聘集成测试使用真实 MySQL 容器验证事务、权限、状态流转及并发行为。运行 Testcontainers 测试需要可用的 Docker 环境。测试通过不代表真实模型质量、外部邮件送达率或生产负载已经验收。

项目不对 QPS、延迟、用户量、成本节省或生产使用情况作未经测量的声明。

重点测试：

- [回答并发与幂等](src/test/java/interview/pilot/interview/application/FixedAnswerBindingIT.java)
- [回答评估数据库租约、回答身份隔离与报告降级](src/test/java/interview/pilot/interview/application/AnswerEvaluationLeaseIT.java)
- [知识库消费、执行租约与索引版本隔离](src/test/java/interview/pilot/knowledge/indexing/KnowledgeIndexListenerIT.java)
- [语音转写执行租约](src/test/java/interview/pilot/voice/infrastructure/VoiceTranscriptionListenerIT.java)
- [语音合成租约与音频隔离](src/test/java/interview/pilot/voice/infrastructure/VoiceSynthesisListenerIT.java)
- [清理运行数据库租约](src/test/java/interview/pilot/async/idempotency/MySqlProcessingClaimIT.java)
- [出题执行代次与迟到结果隔离](src/test/java/interview/pilot/interview/application/QuestionPreparationFencingIT.java)
- [RabbitMQ 重试与死信](src/test/java/interview/pilot/async/messaging/RabbitRetryIT.java)
- [Qdrant 向量召回与范围隔离](src/test/java/interview/pilot/knowledge/retrieval/QdrantVectorRetrievalSourceIT.java)
- [简历分析数据库领权与并发隔离](src/test/java/interview/pilot/resume/application/ResumeAnalysisHandlerTest.java)
- [简历分析 Listener](src/test/java/interview/pilot/resume/application/ResumeAnalysisListenerIT.java)
- [报告 Listener](src/test/java/interview/pilot/async/report/InterviewReportListenerTest.java)
- [固定流程策略](src/test/java/interview/pilot/interview/domain/FixedInterviewFlowPolicyTest.java)
- [回答服务与 SSE 编排](src/test/java/interview/pilot/interview/application/FixedAnswerServiceTest.java)
- [企业权限、投递快照与并发](src/test/java/interview/pilot/recruitment/RecruitmentFoundationIT.java)
- [批次、邀请执行、评审与通知](src/test/java/interview/pilot/recruitment/HiringCampaignIT.java)
- [邮件异常与重试分类](src/test/java/interview/pilot/recruitment/HiringNotificationWorkerTest.java)

## 常见问题

- **候选人看不到企业面试报告：** 企业内部 AI 报告按权限隔离。授权招聘人员需在“面试与评审”单独发布反馈，候选人才可在邀请详情查看。
- **邮件没有发送：** 检查邮件通道开关和企业“通知记录”。`DISABLED` 表示通道未启用；`UNKNOWN` 表示结果不确定，人工确认后才重试。容器运行时还需确认 SMTP 配置已显式传入 `app`。
- **改期后日历没有更新：** ICS 是下载导入方式，需要重新下载并导入；不是日历账号实时同步。
- **没有可用模型：** 为至少一个 Provider 设置非空 API Key 和 `*_ENABLED=true`，并让 `AI_DEFAULT_PROVIDER` 使用相同 ID。Provider Client 在启动时组装，修改后需要重启 App。
- **启动时提示 LLM Lease 无效：** 增大 `LLM_LEASE`，或降低已启用 Provider 的超时时间。Lease 必须大于最长超时时间的两倍。
- **App 一直处于 unhealthy：** 查看 `docker compose logs app mysql redis rabbitmq`。Actuator 会检查基础设施健康状态，凭据错误或依赖不可用都会让 App 保持不健康。
- **简历或报告一直 pending：** 查询任务接口并查看 RabbitMQ 管理页面、数据库中的执行租约及最近错误。失败或进入 `DEAD` 的简历/报告任务可通过 `POST /api/tasks/{taskId}/retry` 重试，不需要清除 Redis 标记；企业和语音任务使用各自的业务重试入口。
- **SSE 响应看起来被缓冲：** 通过项目自带的 Nginx 访问。它已经为 `/api/` 关闭代理缓冲并延长读写超时；其他反向代理也需要保留这些配置。
- **端口被占用：** 在 `.env` 中修改 `FRONTEND_PORT`、`MYSQL_PORT`、`REDIS_PORT`、`RABBITMQ_PORT` 或 `RABBITMQ_MANAGEMENT_PORT`。Spring Boot 端口在 Compose 中不会公开到宿主机。
- **Docker 拉取镜像失败：** 如果日志出现镜像站 EOF 或超时，这是 Docker 镜像源网络问题，不是项目构建错误。检查 Docker Desktop 的 Registry Mirror 配置后重试。
