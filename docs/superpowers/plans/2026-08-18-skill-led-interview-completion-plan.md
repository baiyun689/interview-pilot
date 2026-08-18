# Skill 主导面试补全实施计划

日期：2026-08-18

## 1. 文档目的

本计划用于补全 `2026-08-17-skill-led-interview-refactor.md` 中尚未真正落地的能力。

当前主链路已经可运行：

```text
Skill v4
  -> 动态 InterviewPlan
  -> InterviewStrategy / TurnDirective
  -> 受控 RAG
  -> Question / Evaluation / Report
```

本计划不重复实现已经完成的 Skill 迁移和 RAG 基础闭环，只处理以下剩余问题：

1. 面试流程还没有真正的证据进度状态。
2. 阶段切换主要依赖顺序和轮次，不依赖阶段目标完成度。
3. `TurnDirective` 不是完整的动作指令，重放快照也不够明确。
4. 新版 Skill 与 Prompt、内部兼容模型之间仍有旧语义残留。
5. 检索质量、评测和全量验收还没有形成最终交付门。

## 2. 已完成基线

以下内容视为已完成，不在本计划中重复建设：

- 7 个内置 Skill 已迁移到 schema v4 五文件结构。
- `custom` 保持 legacy 兼容格式。
- Plan 已结合 JD、简历、预算和 Skill 能力池动态编译。
- Plan 不再直接照搬 `defaultCompetencies`。
- Skill stage、competency、evidence、question mode 和 RAG scope 已进入运行时。
- `InterviewStrategy` 已控制首题、追问、切换和结束建议。
- RAG 已支持按 competency 选择、受控 scope、来源快照和不可用回退。
- 问题引用和回答参考事实已经与候选人证据分离。
- revision-safe 索引、旧版本清理、RAG 评测集和 Skill-led E2E 已有第一版实现。

## 3. 补全后的目标架构

不再新增一套平行的 `InterviewExecutionPlan`，而是继续深化现有 `InterviewPlan`，避免出现两个执行计划模型。

```text
SkillSnapshot
  -> InterviewPlan
  -> InterviewProgress
  -> TurnAssessment
  -> InterviewStrategy
  -> TurnDirective
  -> GroundingSnapshot
  -> Question / Evaluation / Report
```

职责边界：

| 模块 | 职责 |
|---|---|
| Skill | 定义岗位能力、证据目标、阶段顺序、追问轴和知识边界 |
| InterviewPlan | 保存本场最终选择的能力、顺序、预算和舍弃理由 |
| InterviewProgress | 保存本场已经获得、缺失和冲突的证据状态 |
| TurnAssessment | 把本轮评价转换成 Java 可执行的证据变化 |
| InterviewStrategy | 根据 Plan + Progress 决定下一动作 |
| TurnDirective | 固定本轮动作、阶段、能力、证据目标和 RAG 指令 |
| GroundingSnapshot | 保存本轮检索状态和来源快照 |
| AI | 生成候选问题、分析回答和提出建议，不拥有流程控制权 |

## 4. 阶段一：证据驱动的面试状态机

### 4.1 目标

让面试流程从“按能力顺序推进”升级为“根据证据完成度推进”。

阶段一完成后，系统必须能够回答：

- 当前 competency 已经验证了哪些证据？
- 还缺哪些证据？
- 当前 stage 是否完成？
- 为什么继续追问、切换能力或结束？

### 4.2 Task 1：建立 `InterviewProgress`

建议文件：

- Create: `src/main/java/interview/pilot/interview/strategy/InterviewProgress.java`
- Create: `src/main/java/interview/pilot/interview/strategy/CompetencyProgress.java`
- Create: `src/main/java/interview/pilot/interview/strategy/TurnAssessment.java`
- Modify: `src/main/java/interview/pilot/interview/domain/AnswerEvaluation.java`

每个 competency 至少记录：

```text
requiredEvidence
observedEvidence
missingEvidence
redFlags
followUpCount
coveredTopics
status: OPEN / SUFFICIENT / EXHAUSTED
```

规则：

- `observedEvidence` 只能来自候选人当前回答及已验证的历史回答。
- RAG 参考事实只能进入 `referenceFacts`，不能进入 `observedEvidence`。
- `missingEvidence` 必须从 Skill 的 evidence 目标中计算，不能由模型自由发明。
- `followUpCount` 由 Java 增加，不能接受模型直接修改。
- Progress 必须是不可变快照，便于重放和审计。

### 4.3 Task 2：让 Strategy 使用证据缺口

建议修改：

- `src/main/java/interview/pilot/interview/strategy/InterviewStrategy.java`
- `src/main/java/interview/pilot/interview/strategy/DefaultInterviewStrategy.java`
- `src/main/java/interview/pilot/interview/application/InterviewDecisionContextFactory.java`
- `src/main/java/interview/pilot/interview/application/SubmitAnswerService.java`

Strategy 决策顺序固定为：

1. 当前回答是否补齐当前 competency 的关键 evidence。
2. 若仍有关键缺口且未达到追问上限，生成定向追问。
3. 若当前 competency 已充分或已耗尽，切换到本 stage 下一个 competency。
4. 当前 stage 的 competency 都完成后，进入下一个 stage。
5. 所有必考能力完成且达到最低证据条件，才允许 `FINISH`。
6. 达到硬轮次上限时允许结束，但必须记录未完成证据。

### 4.4 Task 3：把 `TurnDirective` 明确为动作指令

扩展现有 `TurnDirective`，不新建第二套指令模型：

```text
action: ASK / FINISH
stageId
competency
difficulty
evidenceTargets
probeFocus
questionMode
ragPolicy
reason
```

约束：

- `ASK` 必须有 competency 和 evidence target。
- `FINISH` 必须有 finish reason 和未完成证据摘要。
- AI 返回的 competency、stage、difficulty 和 action 只能作为候选值，最终以 Java Strategy 为准。
- 每次 Strategy 输出必须能由 `Plan + Progress + Assessment` 重算。

### 4.5 Task 4：补充阶段完成规则

不恢复 Skill 中未被代码消费的自然语言 `entryCriteria` / `exitCriteria` 配置。

阶段完成由结构化状态计算：

```text
stage 下所有必选 competency = SUFFICIENT
或 stage 预算耗尽
或当前 stage 无法再产生有效证据
```

阶段切换原因写入 `TurnDirective.reason`，例如：

```text
STAGE_COMPLETED_REQUIRED_EVIDENCE
STAGE_BUDGET_EXHAUSTED
SWITCH_AFTER_LOW_VALUE_FOLLOW_UP
```

### 4.6 阶段一验收标准

- 同一 Plan 在不同回答证据下产生不同的下一轮 Directive。
- 缺失 evidence 会产生对应追问，而不是泛化追问。
- evidence 满足后不会继续重复追问同一目标。
- 当前 stage 完成后才进入下一 stage。
- `FINISH` 能说明已完成能力、未完成能力和结束原因。
- AI 无法越过 Java Strategy 修改流程动作。
- 无 RAG 情况下状态机仍然完整运行。

## 5. 阶段二：重放、清理和最终验收

### 5.1 目标

让每轮面试具有可重放、可审计、可验证的完整快照，并清理旧设计残留。

### 5.2 Task 1：持久化 Progress 和 Directive

建议修改：

- `src/main/java/interview/pilot/interview/infrastructure/InterviewTurnEntity.java`
- `src/main/java/interview/pilot/interview/infrastructure/InterviewSessionEntity.java`
- `src/main/java/interview/pilot/interview/application/SubmitAnswerService.java`
- `src/main/java/interview/pilot/interview/application/InterviewReportHandler.java`
- 对应数据库迁移和 persistence tests

每轮保存：

```text
planSnapshot
progressSnapshot
directiveSnapshot
groundingSnapshot
questionSnapshot
evaluationSnapshot
```

重放规则：

- 相同 requestId 直接返回第一次持久化结果。
- 不重新调用 Planner、Question Generator、Evaluator 或 RAG。
- 发生并发提交时只能产生一个后继 Directive。
- 旧会话没有 Progress/Directive 时使用 legacy decoder。

### 5.3 Task 2：清理旧语义残留

修改：

- `src/main/resources/prompts/interview-plan-system.st`
- `src/main/resources/prompts/question-system.st`
- `src/main/java/interview/pilot/interview/skill/SkillSnapshot.java`
- `src/main/java/interview/pilot/interview/skill/InterviewSkill.java`

清理目标：

- Prompt 不再把 `defaultCompetencies` 描述成岗位覆盖基线。
- Prompt 不再把 `references` 描述成可供模型直接引用的知识内容。
- 新版 Skill 的内部字段命名与 v4 字段保持一致。
- 旧字段只保留在明确的 legacy decoder 中，不继续扩散到新流程。

注意：不重新引入 `allowedTools`、`toolsEnabled`、`ragKeywords`、`allowedUses`、`topK` 等没有岗位差异的配置。

### 5.4 Task 3：完善检索质量门禁

检查并补全：

- Qdrant score 缺失时的明确状态，不再静默使用 `0.0`。
- 所有 RAG scope 必须映射到已注册知识域。
- `topK`、最低分、上下文预算等继续由系统配置统一管理。
- 同一文档允许保留多个高质量相邻片段。
- 评测集覆盖低相关、无命中、不可用、同文档多片段和 revision 切换。

### 5.5 Task 4：完成测试和文档交付门

必须执行：

```powershell
.\gradlew.bat test
Set-Location frontend
pnpm exec vitest run
pnpm build
git diff --check
```

数据库迁移测试失败时，必须单独确认：

- 是否由本计划改动引入；
- 是否由工作区其他未提交迁移引入；
- 是否需要单独建立修复任务。

### 5.6 阶段二验收标准

- 每轮可以从数据库重建 Plan、Progress、Directive、RAG 和 Evaluation 关系。
- 幂等重放不重新产生 AI 或 RAG 外部调用。
- 报告只使用持久化快照，不重新决策。
- Prompt 和内部模型不再误导 Skill 作者使用旧字段。
- RAG scope、score、失败状态和引用均有可测试的边界。
- 后端、前端、持久化、并发和 E2E 测试全部通过，或失败已被明确隔离为独立问题。

## 6. 非目标

本补全计划不做以下事情：

- 不把 LLM 变成自治面试流程控制器。
- 不恢复已经删除的冗余 Skill 配置。
- 不为每个岗位重复设计通用系统规则。
- 不重做知识库前端。
- 不更换 Embedding 模型或引入昂贵的 LLM Rerank。
- 不强行迁移 `custom` Skill。

## 7. 实施顺序

推荐按两个阶段纵向切片推进：

### 阶段一

1. `InterviewProgress`、`CompetencyProgress` 和 `TurnAssessment`。
2. Strategy 根据 evidence 缺口生成追问和阶段切换。
3. `TurnDirective` 增加明确动作和结束理由。
4. 补充单元测试和无 RAG E2E。

### 阶段二

1. 持久化 Progress、Directive 和重放快照。
2. 清理 Prompt 和内部兼容语义。
3. 完成 RAG score、scope 和评测门禁。
4. 执行后端、前端和完整 E2E 验收。

## 8. 完成定义

补全完成后，系统必须能从持久化数据解释：

1. 为什么本场选择这些 competency。
2. 当前 competency 已经获得了哪些证据。
3. 为什么当前 stage 已完成或仍需追问。
4. 为什么本轮使用或不使用 RAG。
5. 为什么继续追问、切换能力或结束。
6. 每个结论分别来自 Skill、Plan、候选人回答还是 RAG 参考资料。
7. 相同请求重放时为什么不会重新产生不同结果。

满足以上条件后，Skill 才不仅是“能力和提示词配置”，而是真正能够通过 Plan、Progress 和 Strategy 统括整场面试。
