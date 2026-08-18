# 如何新增一个面试岗位 Skill

1. 复制 `_template/` 到 `skills/<岗位id>/`（如 `golang-backend`），目录名即 skill id（小写字母数字与连字符）。
2. 填写 `skill.yml`：
   - `displayName`/`description`/`icon`：岗位展示信息。
   - `redFlags`：该岗位常见假证据/危险信号 3~5 条，评估时对照命中，可选但强烈推荐。
   - `competencies`：4~6 个能力，决定"考什么、怎么考"。
     - 必填：`id`（稳定标识）、`name`（展示名）、`objective`（一句话考什么）、`evidence`（收什么证据才算考到位，2~5 条）。
     - 可选：`modes`（出题角度，PROJECT/CONCEPT/MECHANISM/ARCHITECTURE/FAILURE/METRICS/TRADEOFF/CODING，默认 PROJECT+MECHANISM）、`probes`（追问方向，2~6 字中文短语）、`ragScopes`（检索域，必须是 KnowledgeDomains 已注册域，不写=不开 RAG）、`stage`（面试阶段，不写自动归类）。
   - `stages`：面试阶段，绝大多数岗位不用写（内置默认：项目深挖→技术深度→可靠性）；仅当岗位阶段确实不同时自定义（如 system-design）。
3. 写 `SKILL.md` 面试官手册，七节结构：岗位考察重点、决策原则、反套路原则、难度锚点、五级评分锚点、证据规则、RAG 资料使用规则。
4. 启动服务即完成：ID 重复、缺必填字段、ragScopes 未注册都会在启动时直接报错。

需要新的检索域时，在 `interview.pilot.interview.skill.KnowledgeDomains` 的 REGISTERED 集合中登记。
