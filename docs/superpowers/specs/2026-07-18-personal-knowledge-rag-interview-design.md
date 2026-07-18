# AI 面试接入个人知识库 RAG 设计

日期：2026-07-18

## 1. 概述

InterviewPilot 将增加按用户隔离、基于文件的个人知识库，并在 AI 模拟面试中把知识库内容作为隐藏的出题与评分参考。

登录用户可以创建知识库，上传 Markdown、TXT、PDF 或 DOCX 文档，等待系统异步完成向量化，然后在创建面试时选择一个或多个已就绪的知识库。系统在生成首题和每一道后续题前，从所选知识库检索相关文本片段。出题模型将这些片段作为不可信参考资料。用户回答后，评估模型复用出题时保存的同一份检索快照。

RAG 只增强问题生成和答案评估，不控制面试状态。现有 Java 决策策略继续负责追问次数、能力覆盖、难度边界、轮次预算和结束条件。

## 2. 目标

- 增加账号登录和严格的用户数据归属校验。
- 支持每位用户创建私有知识库并上传文档。
- 异步完成文档解析、切片、Embedding 和向量索引。
- 保持 MySQL 为业务事实来源，引入 Qdrant 承担向量检索。
- 创建面试时可以选择个人知识库，也可以不选择。
- 生成首题、追问和换题时使用个人知识作为参考。
- 答案评估复用当前题目生成时保存的检索快照。
- 未选择知识库时保持现有面试行为不变。
- Qdrant 或检索暂时不可用时，已经开始的面试仍能继续。

## 3. 非目标

- 知识库自由问答。
- 独立的知识库八股练习模式。
- 在线笔记编辑。
- 扫描版 PDF 的 OCR。
- 图片、压缩包、旧版 DOC 或网页导入。
- 查询改写、专用 Rerank 模型或基于大模型的 Rerank。
- Token 级模型流式输出。
- OAuth、邮箱验证、密码找回、组织、RBAC、计费或管理员后台。
- 生成面试报告时重新进行 RAG 检索。
- 让模型或检索结果代替 Java 面试决策策略。

## 4. 架构决策

系统保留 MySQL，并新增 Qdrant。

| 存储 | 职责 |
|---|---|
| MySQL | 用户、数据归属、源文档、索引状态、面试知识范围、轮次、报告和持久化异步任务 |
| Qdrant | 文本片段、向量、相似度检索及可过滤元数据 |
| Redis | 登录 Session、限流、并发租约和短期处理 Claim |
| RabbitMQ | 可靠投递文档索引和向量清理任务 |
| Docker Volume | 保存私有原始文档 |

不将现有应用迁移到 PostgreSQL + pgvector，因为这会让全部 Flyway 迁移、持久化集成测试和稳定的面试流程承担与业务无关的迁移风险。

也不采用 MySQL + 独立 PostgreSQL/pgvector，因为这会在跨存储一致性仍然存在的情况下，再引入一种关系型数据库。

Qdrant 作为新的 Docker Compose 服务运行。第一版使用一个名为 `knowledge_chunks_v1` 的共享 Collection，使用余弦相似度和 1024 维向量。不同用户通过强制 Payload 过滤进行逻辑隔离，不为每位用户单独创建 Collection。

## 5. 登录和数据归属

### 5.1 身份认证

身份认证采用 Spring Security、存储在 Redis 中的服务端 Session，以及 HttpOnly Cookie。React 前端和 Spring API 继续由同一个 Nginx 域名提供。

接口：

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/auth/me`

邮箱统一转为小写并作为登录名。密码只保存自适应密码哈希。Session Cookie 使用 `HttpOnly` 和 `SameSite=Lax`；公开 HTTPS 部署时同时启用 `Secure`。所有修改状态的请求继续启用 CSRF 防护。

Redis 不保存持久化用户事实。Redis 数据丢失只会导致用户需要重新登录，不会丢失账号或业务数据。

### 5.2 用户表

`user_account` 包含：

- 内部 `BIGINT` 主键；
- 对外 UUID `user_id`；
- 规范化后的唯一 `email`；
- `password_hash`；
- `display_name`；
- `ACTIVE` 或 `DISABLED` 状态；
- 创建时间、更新时间和乐观锁版本。

数据库迁移先创建一个 `legacy-demo` 用户，把现有数据归属到该用户，然后再将用户外键改为非空，避免破坏已有数据。

### 5.3 用户资源

以下聚合根增加非空的 `user_account_id BIGINT` 外键：

- `resume`；
- `job_profile`；
- `interview_session`；
- `async_task`；
- `knowledge_base`。

面试轮次和报告通过 `interview_session` 继承归属。知识文档通过 `knowledge_base` 继承归属，但使用外部 ID 查询文档时，Repository 仍必须通过拥有者范围进行关联查询。

Repository 和应用层接口必须同时按资源标识和当前用户查询。禁止在 Controller 中先进行不带用户范围的全局查询，再单独判断资源归属。

## 6. 知识库领域

### 6.1 数据表

`knowledge_base` 包含：

- 内部主键和对外 UUID `knowledge_base_id`；
- 内部 `user_account_id` 外键；
- `name` 和可选 `description`；
- `ACTIVE`、`ARCHIVED` 或 `DELETING` 状态；
- 创建时间、更新时间和乐观锁版本。

`knowledge_document` 包含：

- 内部主键和对外 UUID `document_id`；
- `knowledge_base_id` 外键；
- 原始文件名、检测到的内容类型、大小和 SHA-256 内容哈希；
- 私有 `storage_key`；
- 提取后的 `parsed_text`；
- `PROCESSING`、`READY`、`FAILED`、`ARCHIVED` 或 `DELETING` 状态；
- 单调递增的 `index_revision`；
- Embedding Provider、模型、维度和 Collection 版本；
- 切片数量和安全的失败原因；
- 创建时间、更新时间和乐观锁版本。

内容哈希在 `(knowledge_base_id, content_hash)` 范围内唯一。不同用户或不同知识库可以分别上传内容相同的文件。

### 6.2 文件存储

原始文件通过一个较深的 `KnowledgeDocumentStore` 模块保存，其接口只暴露：

- 保存上传文件并返回不透明的存储 Key；
- 打开已保存文件；
- 删除已保存文件。

首个 Adapter 使用持久化 Docker Volume，并按生成的用户 UUID 和文档 UUID 组织文件。用户提供的文件名不能成为磁盘路径。文件没有公开 URL；下载必须经过登录和所有权校验。

支持 Markdown、TXT、PDF 和 DOCX。校验同时检查扩展名、声明的 MIME 类型和实际检测类型。解析复用当前简历模块已经加固的 Tika 配置，包括输入大小限制、提取字符数限制和禁止解析嵌入附件。无法提取文本的扫描版 PDF 以明确的“不支持该内容”错误失败。

### 6.3 索引流程

上传文档时：

1. 认证用户并解析该用户拥有的知识库。
2. 校验并保存原始文件。
3. 在 MySQL 创建 `PROCESSING` 文档和持久化 `KNOWLEDGE_DOCUMENT_INDEX` 异步任务。
4. 提交数据库事务。
5. 通过现有带 Publisher Confirm 的 RabbitMQ Publisher 发布任务。
6. Worker 领取任务，重新检查文档和索引版本，打开并解析文件。
7. Worker 切分规范化文本，分批调用 Embedding Provider，并使用确定性 Point ID Upsert 到 Qdrant。
8. 在短事务中再次检查文档版本，将文档标记为 `READY`。

扩展现有任务扫描、重试队列、死信、Claim、尝试次数和人工重试机制，不重新建设异步基础设施。

新增任务类型：

- `KNOWLEDGE_DOCUMENT_INDEX`；
- `KNOWLEDGE_DOCUMENT_DELETE`。

Point ID 根据文档 UUID、索引版本和切片序号确定性生成。RabbitMQ 重复投递会覆盖同一个 Point，不会产生重复向量。迟到 Worker 不能把新版本覆盖为旧状态。

重新索引时，先写入新版本，再切换文档的活动索引版本。只要仍有活动面试引用旧版本，旧版本 Point 就继续保留；不再有活动引用后再进行垃圾清理。

### 6.4 文本切片与 Embedding

迁移并调整 InterviewGuide 的 `RecursiveTextSplitter` 及其测试。切片优先保留 Markdown 标题和完整句子，依次使用：

1. Markdown 标题；
2. 空行；
3. 换行；
4. 中文句号、问号和感叹号；
5. 英文句子标点；
6. 空格；
7. 固定长度兜底。

初始配置为最大 1600 字符、重叠 200 字符。这些值是可调配置，不是对外接口保证，后续必须通过检索评测集校准。

Embedding 配置与用户选择的 Chat Provider 分离。第一版使用 DashScope `text-embedding-v3`、1024 维和版本化 Collection。面试可以使用 DeepSeek 或 Kimi 生成内容，同时由 DashScope 提供 Embedding。

更换 Embedding 维度或语义模型时，创建新的版本化 Collection，并重新索引文档。不同维度或不同语义空间的向量不能混合存储。

每个 Qdrant Point 包含：

- 对外用户 UUID `user_id`，不是 MySQL 内部外键；
- `knowledge_base_id`；
- `document_id`；
- `index_revision`；
- `chunk_index`；
- 源文件名；
- Embedding 版本；
- 文本片段。

## 7. 安全检索模块

业务调用方不能直接访问 Qdrant，也不能自行拼接元数据过滤条件，只能调用：

```java
RetrievedKnowledge retrieve(
    ValidatedKnowledgeScope scope,
    RetrievalIntent intent
);
```

`KnowledgeScopeResolver` 提供两种安全入口。

创建面试时，它接收已认证用户和客户端提交的知识库 ID，校验所有权、就绪文档和兼容的活动索引版本，然后生成 `ValidatedKnowledgeScope`。

面试进行中，它根据会话固化且属于该用户的文档版本快照重新构造检索范围。即使源文档已经归档或重新索引，只要旧版本仍被当前活动面试引用，就可以继续使用。

客户端提交的裸知识库 ID 列表不能直接传入检索器。

`RetrievalIntent` 描述：

- 当前用途为面试问题生成；
- 目标能力或追问焦点；
- 当前难度；
- 相关的简历和岗位关键词；
- 最近已覆盖主题；
- Top-K 和相似度阈值策略。

检索模块负责：

1. 根据可信的面试状态构造长度受限的查询。
2. 对查询进行 Embedding。
3. 使用强制的 `user_id`、`knowledge_base_id`、`document_id` 和 `index_revision` 条件查询 Qdrant。
4. 移除重复或近似重复内容。
5. 限制单个片段和总上下文长度。
6. 返回强类型片段、分数、来源元数据、延迟和检索状态。

过滤条件构造或带过滤搜索失败时，检索必须失败关闭。禁止先执行无过滤向量搜索，再在 Java 内存中过滤。

初始检索获取 Top 12 候选，经过多样性和去重后最多返回 6 个片段。相似度阈值作为配置，初始值为 0.35，后续通过评测集校准。

模块内部预留 `KnowledgeRanker` seam，但首个 Adapter 只使用向量分数、来源多样性和重复内容移除，不接入额外 Rerank 模型。

## 8. 接入模拟面试

### 8.1 创建面试

`CreateInterviewRequest` 增加可选的知识库 UUID 列表。缺失或空列表时保持现有行为。

用户选择知识库后，创建流程：

1. 解析当前用户拥有的简历和知识库。
2. 固化知识库、就绪文档、活动索引版本和 Embedding 版本。
3. 创建现有岗位画像和面试计划。
4. 根据计划能力、难度、简历相关技能和岗位要求构造首题检索意图。
5. 检索个人知识库。
6. 使用检索结果生成首题。
7. 持久化会话知识范围和首题检索快照。

面试会话通过关联表保存所选知识库，同时保存 JSON 范围快照，记录文档 UUID 和索引版本，使本场面试允许使用的检索范围稳定且可审计。

创建请求示例：

```json
{
  "resumeId": 7,
  "skillId": "java-backend",
  "knowledgeBaseIds": [
    "76b7e032-3ca2-493e-9c9f-505599f3688d"
  ],
  "difficulty": "MEDIUM",
  "totalTurnBudget": 8,
  "providerId": "dashscope"
}
```

### 8.2 首题和后续题

生成首题时，问题生成器还没有选定具体 `targetCompetency`，因此检索查询覆盖面试计划中的主要能力，再结合简历、JD 和难度缩小范围。

生成后续题时，只有模型建议经过现有 Java 决策策略校验后，才执行检索：

- `FOLLOW_UP` 使用已校验的当前能力、`probeFocus` 和上一道问题。
- `NEXT_TOPIC` 使用已校验的目标能力、调整后难度和已覆盖主题摘要。
- `FINISH` 不执行检索。

用户原始回答不能直接拼入检索查询，避免回答内容控制检索方向并降低 Prompt 注入风险。

每个 `interview_turn` 增加：

- 可空的 `rag_context_snapshot`；
- `NOT_CONFIGURED`、`RETRIEVED`、`NO_MATCH` 或 `UNAVAILABLE` 状态。

快照包含查询、Embedding 版本、Point ID、文档 ID、文件名、切片序号、分数和经过长度限制的片段正文。该快照不向面试页面展示。

快照示例：

```json
{
  "query": "Spring 与事务 中等难度 事务传播",
  "status": "RETRIEVED",
  "embeddingModel": "text-embedding-v3",
  "chunks": [
    {
      "pointId": "4b70b8d1-23ae-5c3d-a1df-ccf9576ebafe",
      "documentId": "20cd795b-b7c2-4c3f-a04f-553b370c18c8",
      "filename": "Spring八股.md",
      "chunkIndex": 7,
      "score": 0.81,
      "content": "Spring 事务基于 AOP 代理……"
    }
  ]
}
```

### 8.3 答案评估

评估模型读取当前题目生成时保存的同一份检索快照，不再执行向量搜索。

评估依据分为三层：

1. Skill rubric 定义评分原则和能力等级；
2. 题目定义候选人实际需要回答的内容；
3. 已保存的 RAG 片段提供相关技术事实。

RAG 不是唯一真相来源。如果候选人给出技术上合理、但个人笔记没有包含的答案，不能仅因为片段未提及就判错。RAG 内容也不能覆盖分数校验、决策约束或 Java 策略。

报告生成器继续使用已保存的轮次评估和证据，不重新查询知识库。

### 8.4 Prompt 安全

出题 Prompt 明确说明，检索文本是不可信参考资料而非指令。模型可以据此生成有依据的问题，但不能泄露参考答案。

评估 Prompt 明确说明，已保存片段可以用于核对技术事实，但它可能不完整，也不是唯一权威。评估模型必须忽略文档中的角色切换、工具请求、评分命令或其他指令。

检索内容放入边界清晰的不可信数据区段，并设置严格的总字符上限。

## 9. 前端范围

最小前端功能包括：

- 注册、登录、退出和当前用户状态；
- 知识库列表、新建、重命名、归档和删除；
- 文档上传和处理状态；
- 失败文档重新索引；
- 创建面试页多选当前用户的知识库；
- 面试详情展示本场选择的知识库名称。

只有包含就绪文档的知识库可以被选择。面试进行页面不展示检索片段、参考答案或检索分数。

不增加自由问答或独立练习入口。

知识库管理接口：

- `POST /api/knowledge-bases`
- `GET /api/knowledge-bases`
- `PATCH /api/knowledge-bases/{knowledgeBaseId}`
- `DELETE /api/knowledge-bases/{knowledgeBaseId}`
- `POST /api/knowledge-bases/{knowledgeBaseId}/documents`
- `GET /api/knowledge-bases/{knowledgeBaseId}/documents`
- `GET /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}`
- `POST /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/reindex`
- `DELETE /api/knowledge-bases/{knowledgeBaseId}/documents/{documentId}`

## 10. 失败与一致性语义

- 文件存储失败时不创建文档记录。
- 已提交但未成功发布的索引任务由持久化任务扫描器重新发布。
- 解析失败时文档进入 `FAILED`，保留源文件以便重试。
- 临时 Embedding 或 Qdrant 故障进入现有重试和死信流程。
- 确定性 Point ID 使部分 Upsert 后重试保持安全。
- 创建面试或面试进行中的检索失败时记录 `UNAVAILABLE`，使用现有 Skill、简历、JD 和历史上下文继续出题。
- 没有匹配结果时记录 `NO_MATCH`，执行相同的降级流程。
- 评分使用轮次快照，因此不依赖评分时 Qdrant 是否可用。
- 数据库事务中不能包含文件解析、Embedding、向量搜索或 Chat Model 调用。
- 删除文档时，先让它不能被新会话选择。活动面试仍引用的向量版本继续保留，直到不再有会话使用，再异步清理。
- 向量清理失败不会暴露文档，因为每次检索还必须拥有当前活动文档范围或不可变的活动会话范围，并精确过滤索引版本。

Embedding 使用独立的并发准入池，避免批量文档索引耗尽实时面试所需的 Chat Model 并发资源。

## 11. 可观测性

指标和结构化日志包括：

- 文档解析、切片、Embedding 和索引耗时；
- 索引切片数和任务重试结果；
- 检索耗时、结果数、最高分和 `rag_status`；
- 每道生成问题的 RAG 使用率；
- `NO_MATCH` 和 `UNAVAILABLE` 降级次数；
- Embedding 和 Qdrant 错误数；
- 注入 Prompt 的上下文字符数。

日志可以记录不透明的用户和资源标识，但不能记录密码哈希、Session Cookie、完整源文档、完整检索片段、简历或原始回答。

## 12. 验证方案

### 12.1 登录和所有权

- 用户 A 不能查看、下载、修改、选择、索引或检索用户 B 的知识。
- 用户 A 不能使用用户 B 的简历创建面试。
- 不同用户可以分别上传内容相同的文档。
- 所有外部资源查询都必须包含用户范围。

### 12.2 文档处理

- 覆盖 Markdown、TXT、PDF 和 DOCX 解析。
- 安全拒绝不支持、超限、空内容和伪装格式文件。
- 递归切片保持标题、重叠和最大长度约束。
- 重复投递使用确定性 Point ID，不产生重复向量。
- 旧版本 Worker 不能覆盖新版本状态。
- 失败任务可以通过现有机制重试并进入死信。

### 12.3 检索安全

- Qdrant 查询始终包含用户、知识库、文档和索引版本过滤。
- 过滤失败返回 `UNAVAILABLE`，禁止执行无过滤降级。
- 已删除、失败、不兼容或属于其他用户的文档不能进入已验证范围。
- 上传文档中的 Prompt 注入内容只能作为数据，不能改变系统指令。

### 12.4 面试行为

- 创建面试时，所选知识范围和首题检索快照被一致地持久化。
- 答案评估器收到的快照与当前题目保存的快照完全一致。
- 下一题检索使用 Java 校验后的决策，而不是未经校验的原始模型建议。
- 未选择知识库时，现有面试旅程保持不变。
- 无匹配或 Qdrant 故障时，面试仍能推进。
- 生成报告时只使用已保存评估，不执行检索。

### 12.5 集成测试和质量评测

- MySQL 和 RabbitMQ 继续由现有 Testcontainers 测试覆盖。
- 使用 Qdrant Generic Container 验证真实的带过滤写入、搜索、Upsert 和删除。
- 使用 WireMock 或确定性测试 Adapter 提供 Embedding 和 Chat 响应。
- 端到端编排测试使用 Fake Retriever 保持稳定，真实 Qdrant Adapter 由独立边界测试覆盖。
- 建立小型、版本化的 Java、Spring、MySQL 检索语料，测量 Recall@6 和无匹配行为，再据此调整切片大小、阈值或 Top-K。

## 13. 交付顺序

按以下垂直切片实施：

1. 登录，以及现有资源的用户归属迁移。
2. 知识库和文档管理，以及私有文件存储。
3. 可靠的解析、切片、Embedding 和 Qdrant 索引。
4. 基于已验证知识范围的安全检索。
5. 创建面试时选择知识库，并让首题使用 RAG。
6. 后续问题检索，以及答案评估复用轮次快照。
7. 完成前端、可观测性、检索评测集和失败路径验证。

每个切片都必须保持现有无 RAG 面试旅程可用，并确保外部调用不发生在数据库事务中。
