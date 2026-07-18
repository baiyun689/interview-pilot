# AI 面试个人知识库 RAG 实施路线图

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不破坏现有无 RAG 面试旅程的前提下，让登录用户上传私有知识文档，并在创建模拟面试时选择知识库用于出题和评分参考。

**Architecture:** 计划按三个可独立评审的阶段交付。第一阶段建立登录与用户数据边界；第二阶段交付从文件上传到 Qdrant 安全检索的完整后端；第三阶段把检索接入首题、后续题和答案评估，并完成前端。

**Tech Stack:** Java 21、Spring Boot 4.0.1、Spring Security、Spring Session Data Redis、Spring AI 2.0.0-M4、MySQL 8.4、Qdrant、RabbitMQ、Redis、Flyway、React 18、TypeScript、Vitest、Testcontainers、WireMock

## Global Constraints

- MySQL 始终是用户、文档状态、面试范围和异步任务的业务事实来源。
- Qdrant 只保存可重建的文本片段和向量，Collection 固定为 `knowledge_chunks_v1`。
- 第一版 Embedding 固定为 DashScope `text-embedding-v3`、1024 维。
- 支持且只支持 MD、TXT、PDF、DOCX；单文件上限 10MB；不做 OCR。
- 不实现知识库自由问答、独立八股练习、在线笔记、查询改写或 Rerank。
- 未选择知识库时，现有创建面试、答题、报告和并发幂等行为必须保持不变。
- Qdrant、Embedding 或过滤失败时禁止无过滤检索；面试降级到现有 Skill + 简历 + JD 流程。
- 出题时保存的 RAG 快照必须原样复用于当前题评分；评分阶段不重新检索。
- RAG 不得控制 `FOLLOW_UP`、`NEXT_TOPIC`、`FINISH`、难度或轮次预算。
- 文件解析、Embedding、向量检索和 Chat Model 调用不得发生在数据库事务中。
- 每个阶段执行 `git diff --check`、后端相关测试、前端相关测试，并做一次独立提交。

---

## 阶段计划

1. [登录与数据归属计划](2026-07-18-auth-and-ownership.md)
2. [知识库索引与安全检索计划](2026-07-18-knowledge-indexing-and-retrieval.md)
3. [AI 面试 RAG 接入与前端计划](2026-07-18-interview-rag-integration.md)

必须按顺序执行。阶段二依赖阶段一提供的 `CurrentUser` 和用户范围 Repository；阶段三依赖阶段二提供的 `ValidatedKnowledgeScope`、`KnowledgeRetriever` 和就绪知识库查询接口。

## 阶段验收门

### 阶段一完成

- 两个账号无法查看或操作彼此的简历、面试和异步任务。
- 登录 Cookie、CSRF 和前端路由保护可用。
- 现有数据被迁移到禁用的 `legacy-demo` 用户。

### 阶段二完成

- 用户可以通过后端接口创建知识库、上传文件、观察异步索引状态和重试失败任务。
- Qdrant 真实集成测试证明写入、带租户过滤检索、确定性 Upsert 和删除可用。
- 任一过滤错误都返回 `UNAVAILABLE`，不会执行全库查询。

### 阶段三完成

- 创建面试可以选择个人知识库，首题持久化 RAG 快照。
- 当前题评分复用该快照，后续题在 Java 最终决策之后检索。
- Qdrant 故障时面试可继续；不选知识库时现有端到端旅程不变。
- 前端具备最小知识库管理和创建面试多选入口。
