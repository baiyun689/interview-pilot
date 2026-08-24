# Skill 主导的智能面试与策略化 RAG 两阶段实施计划

日期：2026-08-17

## 1. 目标

在保留现有登录、知识库、面试幂等、异步任务和报告链路的前提下，将 InterviewPilot 从“Skill 文本与 RAG 片段共同注入模型”重构为：

```text
Skill 定义面试方法和约束
  -> Plan 编译本场执行路线
  -> InterviewStrategy 控制每轮目标和动作
  -> RAG 按策略提供受控参考资料
  -> AI 完成出题、回答分析和报告表达
  -> Java 校验并持久化最终事实
```

本计划只分两个阶段：

1. **阶段一：Skill 主导的面试执行链路**——优先解决 Plan 照搬 `defaultCompetencies`、Skill 运行时字段未生效和 AI 掌握过多流程决策的问题。
2. **阶段二：策略化 RAG 与可靠性闭环**——让 RAG 服从 Skill/Plan，并补齐来源、证据隔离、检索质量和索引一致性。

## 2. 当前问题基线

- `skill.meta.yml` 中的 `defaultStages`、`retrievalScopes`、`ragKeywords`、`allowedTools` 和 `runtime` 未进入 `InterviewSkill`/`SkillSnapshot`，运行时基本不生效。
- 当前 `InterviewPlan` 只有能力列表和轮次预算，无法表达阶段、优先级、轮次分配、证据目标、问题模式和 RAG 策略。
- Plan Prompt 要求默认保留 `skill.defaultCompetencies`，模型最稳妥的输出就是照搬预设能力。
- 出题器、评估器和 Java 决策策略之间缺少统一的“本轮指令”，职责分散在 Prompt 和 `SubmitAnswerService` 中。
- RAG 是额外上下文注入，没有明确区分候选人证据、技术参考和岗位约束。
- 检索分数固定为 `0.0`，去重会丢失同文档有效片段，配置项未真正生效，重新索引存在 revision 竞态。

## 3. 全局设计原则

- **Skill 是规则模板，不保存单场面试状态。**
- **Plan 是 Skill 针对当前 JD、简历、难度和轮次预算的不可变执行快照。**
- **InterviewStrategy 是唯一流程决策模块。** 现有 `InterviewDecisionPolicy` 作为其内部规则复用，不再形成第二套流程大脑。
- **AI 只返回结构化分析或候选方案。** 必考覆盖、轮次、追问上限、结束条件和证据归属由 Java 校验。
- **RAG 只提供参考资料。** 它不能声明候选人经历，不能决定 `FOLLOW_UP`、`NEXT_TOPIC` 或 `FINISH`。
- **历史可复现。** 每场面试保存 Skill 版本和完整 Plan；Skill 后续升级不改变历史会话。
- **兼容无 RAG 路径。** 未选知识库或检索不可用时，面试仍由 Skill + Plan 正常推进。
- **外部调用不进入数据库事务。** Chat、Embedding、Qdrant 和文件解析继续在事务外执行。

## 4. 目标模块与接口

### 4.1 SkillCatalog 模块

职责：加载、校验、版本化和快照化可执行 Skill。

主要输出：

```java
public record SkillSnapshot(
    int schemaVersion,
    String id,
    String name,
    String description,
    SkillGroup group,
    List<SkillStageSpec> stages,
    List<CompetencySpec> competencies,
    SkillRubric rubric,
    SkillRetrievalPolicy retrievalPolicy,
    String persona,
    String version) {}
```

`CompetencySpec` 至少包含：

- 稳定 ID 和展示名称；
- 目标描述；
- 必须收集的证据；
- 推荐问题模式；
- 追问轴；
- 红旗信号；
- 最大连续追问次数；
- 能力级 RAG 策略覆盖。

### 4.2 InterviewPlanCompiler 模块

职责：将通用 Skill 编译为本场面试的执行快照。

接口：

```java
InterviewExecutionPlan compile(PlanCompilationRequest request);
```

输入：Skill 快照、JD 要求、简历画像、难度、轮次预算和可选 AI 规划建议。

输出至少包含：

- 选中的能力及选择理由；
- 阶段和执行顺序；
- 每项能力轮次预算；
- 本场证据目标；
- 问题模式；
- RAG 使用策略；
- 总轮次与必考覆盖证明。

### 4.3 InterviewStrategy 模块

职责：根据 Plan 和已收集证据，返回唯一的下一轮执行指令。

接口：

```java
TurnDirective firstTurn(InterviewExecutionPlan plan);

TurnDirective nextTurn(
    InterviewExecutionPlan plan,
    InterviewProgress progress,
    TurnAssessment assessment);
```

`TurnDirective` 至少包含：

- `ASK` 或 `FINISH`；
- 当前 stage、competency 和 difficulty；
- 本轮证据目标；
- `PROJECT`、`MECHANISM`、`FAILURE`、`METRICS`、`TRADEOFF` 等问题模式；
- RAG 指令；
- 选择本动作的机器可审计原因。

### 4.4 KnowledgeGrounding 模块

职责：消费 `TurnDirective`，安全完成检索、质量过滤、来源快照和降级。

接口保持小而稳定：

```java
GroundingSnapshot ground(
    ValidatedKnowledgeScope scope,
    GroundingDirective directive);
```

调用方不再自行拼接 Query、Top-K、阈值或 Qdrant Filter。

## 5. 阶段一：Skill 主导的面试执行链路

### 5.1 阶段目标

交付一条不依赖 RAG 也能稳定运行的新主链路，使 Skill 真正控制“考什么、收集什么证据、如何追问和何时结束”。

阶段一完成后，最重要的行为变化是：不同 JD、简历、难度和轮次预算会生成不同的执行 Plan，而不是照搬 Skill 默认能力。

### 5.2 实施任务

#### Task 1：定义并加载新版 Skill Schema

**主要文件：**

- Modify: `src/main/java/interview/pilot/interview/skill/InterviewSkill.java`
- Modify: `src/main/java/interview/pilot/interview/skill/SkillSnapshot.java`
- Modify: `src/main/java/interview/pilot/interview/skill/ClasspathInterviewSkillCatalog.java`
- Modify: `src/main/resources/skills/*/skill.meta.yml`
- Test: `src/test/java/interview/pilot/interview/skill/ClasspathInterviewSkillCatalogTest.java`

任务：

- [ ] 增加 `schemaVersion`、结构化 stages、competencies、rubric 和 retrieval policy。
- [ ] 校验稳定 competency ID、stage 引用、证据目标、追问上限和 RAG scope。
- [ ] Skill `version` 哈希覆盖所有运行时字段，避免修改 `defaultStages` 等字段却不改变版本。
- [ ] 对旧 Skill 配置提供启动期转换，迁移完成后删除转换代码。
- [ ] 先完整重构 `ai-agent-dev` 和 `java-backend`，其余 Skill 按同一 Schema 迁移。

新版 `skill.meta.yml` 的最小形态：

```yaml
schemaVersion: 2
displayName: AI Agent 开发
group: JOB

stages:
  - id: project_deep_dive
    purpose: 获取真实项目和个人贡献证据
  - id: architecture
    purpose: 验证机制、架构和方案取舍
  - id: reliability
    purpose: 验证失败处理和生产可靠性

competencies:
  - id: rag_design
    name: RAG 设计
    requiredEvidence:
      - chunking_rationale
      - retrieval_and_rerank
      - evaluation_metrics
      - provenance_and_freshness
    questionModes: [PROJECT, MECHANISM, FAILURE, METRICS, TRADEOFF]
    followUpLimit: 2
    redFlags:
      - only_mentions_vector_search
    rag:
      enabled: true
      scopes: [rag, search]
      allowedUses: [GENERATE_SCENARIO, VERIFY_FACT]
```

#### Task 2：将 InterviewPlan 改为编译结果

**主要文件：**

- Modify: `src/main/java/interview/pilot/interview/domain/InterviewPlan.java`
- Create: `src/main/java/interview/pilot/interview/application/InterviewPlanCompiler.java`
- Create: `src/main/java/interview/pilot/interview/application/PlanCompilationRequest.java`
- Modify: `src/main/java/interview/pilot/interview/application/InterviewPlanCompiler.java`
- Modify: `src/main/java/interview/pilot/interview/application/CreateInterviewService.java`
- Test: `src/test/java/interview/pilot/interview/application/CreateInterviewServiceTest.java`
- Test: `src/test/java/interview/pilot/interview/application/AiInterviewComponentsTest.java`

任务：

- [ ] 引入 `InterviewExecutionPlan`，包含阶段、能力、轮次分配、证据目标、问题模式和 RAG 指令。
- [ ] Java 先合并 Skill 能力池与 JD 必考项，建立硬约束。
- [x] 由确定性编译器直接根据 Skill、JD、简历和预算生成最终 Plan，不再设置独立的模型决策阶段。
- [ ] 编译器校验总轮次、必考覆盖、能力合法性和每项能力至少一个证据目标。
- [ ] 禁止“默认保留全部 defaultCompetencies”；轮次不足时按明确规则舍弃低优先级非必考项。
- [ ] 为旧 `plan_snapshot` 增加 legacy decoder，使历史会话仍可查询和生成报告。

建议的确定性轮次分配规则：

1. JD 必考项先分配每项至少一轮。
2. 项目证据阶段在有简历时保留至少一轮。
3. 剩余轮次按岗位相关度、简历证据和 Skill 优先级加权分配。
4. 无法容纳的非必考项明确记录为 `omittedCompetencies`，不得静默丢失。

#### Task 3：引入统一 InterviewStrategy

**主要文件：**

- Create: `src/main/java/interview/pilot/interview/strategy/InterviewStrategy.java`
- Create: `src/main/java/interview/pilot/interview/strategy/TurnDirective.java`
- Create: `src/main/java/interview/pilot/interview/strategy/InterviewProgress.java`
- Modify: `src/main/java/interview/pilot/interview/domain/InterviewDecisionPolicy.java`
- Modify: `src/main/java/interview/pilot/interview/application/CreateInterviewService.java`
- Modify: `src/main/java/interview/pilot/interview/application/SubmitAnswerService.java`
- Test: `src/test/java/interview/pilot/interview/strategy/InterviewStrategyTest.java`

任务：

- [ ] 首题由 Strategy 根据 Plan 产生 `TurnDirective`，不再让问题模型自由选择能力。
- [ ] 评估后将 AI 建议转换为 `TurnAssessment`，Strategy 再决定追问、换题或结束。
- [ ] 复用现有 `InterviewDecisionPolicy` 的置信度、覆盖和追问限制，但收进 Strategy 实现内部。
- [ ] Strategy 根据 required evidence 缺口选择追问轴，而不是只依赖模型的 `probeFocus`。
- [ ] 所有 `FINISH` 必须满足必考能力覆盖、最低证据条件或硬轮次上限之一。
- [ ] `SubmitAnswerService` 退化为编排模块，不再自己构造能力选择和 RAG Query。

#### Task 4：调整出题、评估和报告契约

**主要文件：**

- Modify: `src/main/java/interview/pilot/interview/domain/GeneratedQuestion.java`
- Modify: `src/main/java/interview/pilot/interview/domain/AnswerEvaluation.java`
- Modify: `src/main/java/interview/pilot/interview/application/AiQuestionGenerator.java`
- Modify: `src/main/java/interview/pilot/interview/application/AiAnswerEvaluator.java`
- Modify: `src/main/java/interview/pilot/interview/application/AiReportGenerator.java`
- Modify: `src/main/resources/prompts/*.st`
- Test: `src/test/java/interview/pilot/interview/application/AiInterviewComponentsTest.java`

任务：

- [ ] 问题模型必须消费 `TurnDirective`，不得修改 stage、competency、difficulty 和证据目标。
- [ ] 评估结果区分 `observedEvidence`、`missingEvidence`、`redFlags` 和模型建议。
- [ ] Java 校验 `observedEvidence` 必须能在当前回答中找到依据，不能直接继承 RAG 或历史文本。
- [ ] 报告按 Plan 能力和证据目标汇总，不按模型临时生成的能力名称汇总。
- [ ] 保留现有结构化输出重试和 Bean Validation。

#### Task 5：持久化、兼容和端到端回归

**主要文件：**

- Modify: `src/main/java/interview/pilot/interview/infrastructure/InterviewSessionEntity.java`
- Modify: `src/main/java/interview/pilot/interview/infrastructure/JpaInterviewCreationStore.java`
- Modify: `src/main/java/interview/pilot/interview/application/StoredAnswerResultCodec.java`
- Test: `src/test/java/interview/pilot/persistence/*`
- Test: `src/test/java/interview/pilot/e2e/InterviewJourneyIT.java`

任务：

- [ ] 持久化新版 Skill、Plan schema version 和每轮 `TurnDirective` 快照。
- [ ] 旧会话继续按 legacy plan 读取；新会话只写新版格式。
- [ ] 幂等重放必须返回第一次持久化的 Directive、评估和下一题，不能重新规划。
- [ ] 并发提交仍只能产生一个下一轮。
- [ ] 无知识库的现有 E2E 旅程保持可用。

### 5.3 阶段一验收标准

- [ ] 同一 Skill 在不同 JD、简历、难度和轮次预算下产生明显不同的执行 Plan。
- [ ] 最终 Plan 不要求保留全部 Skill 默认能力，并明确记录舍弃理由。
- [ ] 每道题都有固定 stage、competency、question mode 和 evidence targets。
- [ ] AI 无法越过 Strategy 修改能力、追问上限、必考覆盖或结束条件。
- [ ] 评估结果能明确显示本轮新增证据、缺失证据和红旗信号。
- [ ] 历史会话可读取，新会话可幂等重放。
- [ ] 后端相关单元测试、持久化测试、并发测试和无 RAG E2E 全部通过。

### 5.4 阶段一非目标

- 不在此阶段更换 Embedding 模型或 Qdrant。
- 不在此阶段实现专用 Rerank 模型。
- 不要求所有 Skill 一开始达到同等内容质量；先以 `ai-agent-dev` 和 `java-backend` 验证 Schema 和执行链路。
- 不在此阶段重做知识库前端。

## 6. 阶段二：策略化 RAG 与可靠性闭环

### 6.1 阶段目标

让 RAG 完全服从 `TurnDirective`，形成“受控检索—来源快照—出题/核验—证据隔离—可观测评估”的闭环，并修复当前底层检索和索引一致性问题。

### 6.2 实施任务

#### Task 1：建立知识角色和检索指令

**主要文件：**

- Create: `src/main/java/interview/pilot/interview/grounding/GroundingDirective.java`
- Create: `src/main/java/interview/pilot/interview/grounding/GroundingSnapshot.java`
- Create: `src/main/java/interview/pilot/interview/grounding/KnowledgeGrounding.java`
- Modify: `src/main/java/interview/pilot/knowledge/retrieval/RetrievalIntent.java`
- Modify: `src/main/java/interview/pilot/knowledge/retrieval/ValidatedKnowledgeScope.java`
- Modify: `src/main/java/interview/pilot/interview/application/CreateInterviewService.java`
- Modify: `src/main/java/interview/pilot/interview/application/SubmitAnswerService.java`

任务：

- [ ] 明确三类知识角色：`CANDIDATE_EVIDENCE`、`TECHNICAL_REFERENCE`、`INTERVIEW_CONSTRAINT`。
- [ ] 旧知识库默认迁移为 `TECHNICAL_REFERENCE`，避免误称为候选人经历。
- [ ] Skill 的 retrieval policy 与本场知识范围共同生成 `GroundingDirective`。
- [ ] Query 由当前能力、问题模式、证据缺口、已覆盖主题和难度构造；原始回答不得直接拼接。
- [ ] `NO_MATCH`、`UNAVAILABLE`、`NOT_REQUESTED` 和 `DISABLED` 使用不同状态。
- [ ] 调用方只消费 `GroundingSnapshot`，不接触 Qdrant Filter 和检索参数。

#### Task 2：建立来源引用和证据隔离

**主要文件：**

- Modify: `src/main/java/interview/pilot/interview/rag/RagContextSnapshot.java`
- Modify: `src/main/java/interview/pilot/interview/domain/GeneratedQuestion.java`
- Modify: `src/main/java/interview/pilot/interview/domain/AnswerEvaluation.java`
- Modify: `src/main/java/interview/pilot/interview/application/AiQuestionGenerator.java`
- Modify: `src/main/java/interview/pilot/interview/application/AiAnswerEvaluator.java`
- Modify: `src/main/resources/prompts/question-system.st`
- Modify: `src/main/resources/prompts/answer-evaluation-system.st`

任务：

- [ ] 每个片段保存稳定 source ID、知识角色、文档版本、章节/页码和真实分数。
- [ ] `GeneratedQuestion` 增加 `groundingMode` 和 `evidenceRefs`。
- [ ] Java 校验 `evidenceRefs` 必须属于当前快照；无引用的问题标记为 Skill 通用题。
- [ ] 评估明确分离：候选人回答证据、参考事实、缺失证据和冲突事实。
- [ ] 技术参考只能核验事实，不能成为 `observedEvidence`。
- [ ] 报告使用已保存引用，不在报告阶段重新检索。

#### Task 3：修复检索质量

**主要文件：**

- Modify: `src/main/java/interview/pilot/knowledge/retrieval/QdrantKnowledgeRetriever.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/KnowledgeRanker.java`
- Modify: `src/main/java/interview/pilot/knowledge/config/KnowledgeProperties.java`
- Test: `src/test/java/interview/pilot/knowledge/retrieval/QdrantKnowledgeRetrieverIT.java`

任务：

- [ ] 使用能返回 score 的 Qdrant Adapter，移除固定 `0.0` 分数。
- [ ] 先按真实相关度排序，再执行近重复消除和来源多样性控制。
- [ ] 允许同一文档保留多个高质量相邻片段，不再强制每文档一个 chunk。
- [ ] `topK`、候选数、最小分数和上下文字符预算统一由配置与 Skill 策略决定。
- [ ] `competency`、`difficulty`、`keywords` 和 `excludeTopics` 要么真正参与查询构造，要么从接口删除。
- [ ] 增加低相关、同文档多片段、多文档排序、无命中和过滤失败测试。

#### Task 4：修复 revision-safe 索引和清理

**主要文件：**

- Modify: `src/main/java/interview/pilot/knowledge/indexing/KnowledgeIndexer.java`
- Modify: `src/main/java/interview/pilot/knowledge/indexing/KnowledgeIndexHandler.java`
- Create: `src/main/java/interview/pilot/knowledge/indexing/KnowledgeRevisionCleanup.java`
- Test: `src/test/java/interview/pilot/knowledge/indexing/KnowledgeIndexerTest.java`
- Test: `src/test/java/interview/pilot/knowledge/indexing/KnowledgeIndexListenerIT.java`

任务：

- [ ] Point ID 包含 document ID、revision 和 chunk index，重复投递执行确定性 Upsert。
- [ ] 先完整写入新 revision，再在 MySQL 原子切换活动版本。
- [ ] 索引 Worker 在外部写入前后都检查 revision fence。
- [ ] 禁止旧 Worker 按 `document_id` 删除新 revision 向量。
- [ ] 旧 revision 在没有活动面试引用后异步清理；失败可重试且不影响新版本可用性。
- [ ] 增加旧 Worker 晚到、部分写入重试、删除失败和活动会话引用测试。

#### Task 5：质量评估、可观测性和端到端验收

**主要文件：**

- Modify: `src/main/java/interview/pilot/common/observability/AiMetrics.java`
- Create: `src/test/resources/rag-evaluation/*`
- Create: `src/test/java/interview/pilot/e2e/SkillLedRagInterviewJourneyIT.java`
- Modify: `frontend/src/pages/InterviewReportPage.tsx`（仅展示安全的来源摘要时需要）

任务：

- [ ] 建立版本化的小型检索评测集，覆盖 Java、Spring、RAG 和系统设计。
- [ ] 记录 Recall@K、MRR、NO_MATCH 准确性、检索延迟和注入字符数。
- [ ] 记录每个 Skill/competency 的 RAG 使用率、降级率和引用率。
- [ ] 端到端验证 Skill -> Plan -> Strategy -> RAG -> Question -> Evaluation -> Report 全链路。
- [ ] 验证检索不可用时，Strategy 仍能根据 Skill 生成通用问题并完成面试。
- [ ] 日志不得输出完整简历、回答、文档片段或隐藏评分标准。

### 6.3 阶段二验收标准

- [ ] Skill 可以控制每个能力是否使用 RAG、允许的用途和知识范围。
- [ ] 没有高质量召回时自动生成 Skill 通用题，不强行使用低相关片段。
- [ ] 每道知识辅助题都能追溯到当前轮次快照中的合法 source ID。
- [ ] 候选人评分证据只来自候选人回答，技术参考只用于事实核验。
- [ ] 检索返回真实分数，配置项生效，同文档有效上下文不会被错误去重。
- [ ] 并发重新索引、失败重试和旧 Worker 晚到不会破坏当前活动 revision。
- [ ] Qdrant 或 Embedding 故障时，面试可继续且状态可观测。
- [ ] 检索评测集和完整 E2E 测试通过。

### 6.4 阶段二非目标

- 不实现知识库自由问答。
- 不引入自治 Agent 或让模型直接调用任意工具。
- 不在首版接入昂贵的 LLM Rerank；先用真实向量分数、去重和多样性排序。
- 不向面试进行页面泄露参考答案、完整片段或隐藏 rubric。

## 7. 阶段依赖与交付门

阶段二必须建立在阶段一稳定接口上：

```text
SkillSnapshot
  -> InterviewExecutionPlan
  -> TurnDirective
  -> GroundingDirective
  -> GroundingSnapshot
```

阶段一通过验收后，`TurnDirective` 成为阶段二唯一的 RAG 业务输入。阶段二不得重新从 Prompt、原始回答或 Controller 参数推断当前面试目标。

每阶段完成时执行：

```powershell
.\gradlew.bat test
Set-Location frontend
pnpm exec vitest run
pnpm build
```

并执行：

```powershell
git diff --check
```

## 8. 推荐实施顺序与并行策略

为缩短总工期，阶段内部按纵向切片推进：

### 阶段一

1. 新 Skill Schema 与加载测试。
2. `ai-agent-dev` 迁移并实现 Plan Compiler。
3. Strategy 接入首题和后续题。
4. 评估/报告契约和持久化兼容。
5. `java-backend` 迁移及完整回归。
6. 其余 Skill 在 Schema 稳定后并行迁移。

### 阶段二

1. Grounding 接口和知识角色。
2. 来源引用与证据隔离。
3. 检索 score/排序修复与 revision-safe 索引可并行实施。
4. 最后汇合到评测集、可观测性和 E2E。

## 9. 主要风险与控制措施

| 风险 | 控制措施 |
|---|---|
| 新 Plan JSON 破坏历史会话 | schema version + legacy decoder；新写旧读兼容 |
| Skill Schema 过度复杂导致维护困难 | 首版只保留流程真正消费的字段；启动时严格校验 |
| Strategy 与现有 DecisionPolicy 双重决策 | DecisionPolicy 收入 Strategy 内部，只暴露一个外部接口 |
| AI 规划建议再次退化为复制默认能力 | AI 只输出排序和理由；最终选择与轮次由 Java 编译器完成 |
| RAG 资料污染候选人证据 | 强类型知识角色 + 分离输出字段 + Java 引用校验 |
| 大范围重构影响幂等和并发 | 保持 claim/finalize 事务模型；新增快照参与重放测试 |
| 索引修复影响活动面试 | revision-specific Point + 先写后切换 + 延迟清理 |

## 10. 完成定义

两个阶段全部完成后，系统必须能完整解释并复现：

1. 为什么本场选择这些能力并舍弃其他能力；
2. 为什么当前轮次处于这个阶段并提出这道问题；
3. 本轮希望收集哪些候选人证据；
4. 是否使用 RAG、使用了哪些来源、用途是什么；
5. 哪些评分证据确实来自候选人回答；
6. 为什么继续追问、切换能力或结束面试；
7. Skill、Plan、Directive、RAG 快照和最终报告之间如何对应。

达到以上条件，Skill 才真正承担整场面试的统括职责，Plan 成为可执行的场次快照，RAG 成为受控且可解释的参考资料模块。
