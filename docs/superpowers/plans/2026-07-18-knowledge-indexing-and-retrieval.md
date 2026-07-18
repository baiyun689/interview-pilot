# 知识库索引与安全检索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供从私有文件上传到 Qdrant 带用户过滤检索的完整后端能力。

**Architecture:** MySQL 保存知识库、文档、索引版本和任务状态；Docker Volume 保存原文件；RabbitMQ 异步解析和向量化；Qdrant 保存可重建向量。调用方必须先通过 `KnowledgeScopeResolver` 得到 `ValidatedKnowledgeScope`，才能调用检索接口。

**Tech Stack:** Spring AI、Qdrant、DashScope Embedding、Apache Tika、RabbitMQ、Flyway、Testcontainers

## Global Constraints

- Collection 固定为 `knowledge_chunks_v1`，向量维度固定为 1024，距离为 COSINE。
- 每个 Point 必须包含外部 `user_id`、`knowledge_base_id`、`document_id`、`index_revision`、`chunk_index` 和源文件名。
- 任一搜索必须同时过滤用户、知识库、文档和索引版本；过滤失败禁止无过滤回退。
- 只索引当前用户拥有且状态允许处理的文档。
- 文档解析、Embedding 和 Qdrant 写入都在事务外执行。

---

### Task 1: Qdrant、Embedding 和文件卷基础配置

**Files:**
- Modify: `build.gradle`
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/interview/pilot/knowledge/config/KnowledgeProperties.java`
- Create: `src/main/java/interview/pilot/knowledge/config/EmbeddingConfiguration.java`
- Test: `src/test/java/interview/pilot/InfrastructureConfigurationTest.java`

**Interfaces:**
- Produces: `EmbeddingModel knowledgeEmbeddingModel`。
- Produces: `VectorStore knowledgeVectorStore`。
- Produces: `KnowledgeProperties`，包含文件根目录、切片、批次、Collection、Top-K 和阈值。

- [ ] **Step 1: 扩展失败的基础设施配置测试**

```java
assertTrue(compose.contains("qdrant/qdrant:"));
assertTrue(compose.contains("knowledge_files:"));
assertTrue(application.contains("collection-name: knowledge_chunks_v1"));
assertTrue(application.contains("dimensions: 1024"));
assertTrue(environmentExample.contains("DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3"));
```

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.InfrastructureConfigurationTest`

Expected: FAIL，配置尚不存在。

- [ ] **Step 3: 添加依赖和 Compose 服务**

```groovy
implementation 'org.springframework.ai:spring-ai-starter-vector-store-qdrant:2.0.0-M4'
```

```yaml
qdrant:
  image: qdrant/qdrant:v1.15.4
  volumes:
    - qdrant_data:/qdrant/storage
  expose: ["6333", "6334"]
  healthcheck:
    test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/6333"]
    interval: 10s
    timeout: 3s
    retries: 10

app:
  volumes:
    - knowledge_files:/app/data/knowledge
```

Compose 的 `volumes` 增加 `qdrant_data` 和 `knowledge_files`，App 增加 Qdrant 与 Embedding 环境变量。

- [ ] **Step 4: 实现独立 Embedding 配置**

使用 InterviewGuide 已验证的构造方式创建 `OpenAiEmbeddingModel`：

```java
OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
    .model(properties.embedding().model())
    .dimensions(1024)
    .build();
return new OpenAiEmbeddingModel(
    openAiApi, MetadataMode.EMBED, options,
    RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);
```

Qdrant `VectorStore` 由 `QdrantVectorStore.builder(client, knowledgeEmbeddingModel)`
创建，指定 Collection 和 `initializeSchema=true`。测试环境通过属性关闭该配置并注入 Fake。

- [ ] **Step 5: 运行配置测试和上下文测试**

Run: `.\gradlew.bat test --tests interview.pilot.InfrastructureConfigurationTest --tests interview.pilot.InterviewPilotApplicationTest`

Expected: PASS；未配置 Embedding Key 时应用测试仍不暴露自动配置模型。

- [ ] **Step 6: 提交**

```bash
git add build.gradle docker-compose.yml .env.example src/main/resources/application.yml src/main/java/interview/pilot/knowledge/config src/test/java/interview/pilot/InfrastructureConfigurationTest.java
git commit -m "build: add qdrant and embedding configuration"
```

### Task 2: 知识库和文档持久化

**Files:**
- Create: `src/main/resources/db/migration/V12__create_knowledge_base.sql`
- Create: `src/main/java/interview/pilot/knowledge/domain/KnowledgeBaseStatus.java`
- Create: `src/main/java/interview/pilot/knowledge/domain/KnowledgeDocumentStatus.java`
- Create: `src/main/java/interview/pilot/knowledge/infrastructure/KnowledgeBaseEntity.java`
- Create: `src/main/java/interview/pilot/knowledge/infrastructure/KnowledgeDocumentEntity.java`
- Create: `src/main/java/interview/pilot/knowledge/infrastructure/KnowledgeBaseRepository.java`
- Create: `src/main/java/interview/pilot/knowledge/infrastructure/KnowledgeDocumentRepository.java`
- Test: `src/test/java/interview/pilot/persistence/KnowledgeSchemaV12MigrationIT.java`

**Interfaces:**
- Produces: `findByKnowledgeBaseIdAndUserAccountId(UUID, Long)`。
- Produces: `findReadyByKnowledgeBaseIdsAndUserAccountId(Collection<UUID>, Long)`。
- Produces: 文档 `beginReindex()`、`markReady(revision, ...)`、`markFailed(revision, ...)` 版本围栏。

- [ ] **Step 1: 写失败的 schema 测试**

```java
@Test
void knowledgeTablesEnforcePerBaseHashAndStatuses() {
  assertThat(tableExists("knowledge_base")).isTrue();
  assertThat(tableExists("knowledge_document")).isTrue();
  assertThat(indexExists("knowledge_document", "uq_knowledge_document_base_hash")).isTrue();
}
```

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.persistence.KnowledgeSchemaV12MigrationIT`

Expected: FAIL。

- [ ] **Step 3: 创建迁移**

V12 创建两个表、外键、状态 CHECK、UUID 唯一键、`(knowledge_base_id, content_hash)` 唯一键和用户列表索引。`knowledge_document` 保存
`storage_key`、`parsed_text LONGTEXT`、`index_revision`、Embedding 快照、`chunk_count`、
`failure_reason` 和 `version`。

- [ ] **Step 4: 实现实体状态转换**

```java
public int beginReindex() {
  if (status == KnowledgeDocumentStatus.DELETING) {
    throw new IllegalStateException("Deleting document cannot be reindexed");
  }
  indexRevision++;
  status = KnowledgeDocumentStatus.PROCESSING;
  failureReason = null;
  return indexRevision;
}

public void markReady(int expectedRevision, String parsedText, int chunkCount) {
  requireRevision(expectedRevision);
  this.parsedText = parsedText;
  this.chunkCount = chunkCount;
  this.status = KnowledgeDocumentStatus.READY;
}
```

- [ ] **Step 5: 运行迁移和实体测试**

Run: `.\gradlew.bat test --tests interview.pilot.persistence.KnowledgeSchemaV12MigrationIT --tests 'interview.pilot.knowledge.infrastructure.*'`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/db/migration/V12__create_knowledge_base.sql src/main/java/interview/pilot/knowledge src/test/java/interview/pilot/persistence/KnowledgeSchemaV12MigrationIT.java src/test/java/interview/pilot/knowledge
git commit -m "feat: add knowledge base persistence"
```

### Task 3: 私有文件存储、解析和递归切片

**Files:**
- Create: `src/main/java/interview/pilot/knowledge/storage/KnowledgeDocumentStore.java`
- Create: `src/main/java/interview/pilot/knowledge/storage/FileSystemKnowledgeDocumentStore.java`
- Create: `src/main/java/interview/pilot/knowledge/indexing/KnowledgeDocumentParser.java`
- Create: `src/main/java/interview/pilot/knowledge/indexing/RecursiveTextSplitter.java`
- Test: `src/test/java/interview/pilot/knowledge/storage/FileSystemKnowledgeDocumentStoreTest.java`
- Test: `src/test/java/interview/pilot/knowledge/indexing/KnowledgeDocumentParserTest.java`
- Test: `src/test/java/interview/pilot/knowledge/indexing/RecursiveTextSplitterTest.java`

**Interfaces:**
- Produces: `String store(UUID userId, UUID documentId, MultipartFile file)`。
- Produces: `InputStream open(String storageKey)`、`void delete(String storageKey)`。
- Produces: `String parse(InputStream, filename, size)`。
- Produces: `List<String> split(String text)`。

- [ ] **Step 1: 迁移 InterviewGuide 切片测试并补文件路径测试**

```java
@Test
void storageKeyNeverContainsTheUserFilename() {
  String key = store.store(userId, documentId, multipart("../../secret.md", "# JVM"));
  assertThat(key).isEqualTo(userId + "/" + documentId + "/source");
  assertThat(root.resolve(key).normalize()).startsWith(root);
}
```

切片测试覆盖 Markdown 标题、中文标点、1600 上限、200 重叠和空文本。

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests 'interview.pilot.knowledge.storage.*' --tests 'interview.pilot.knowledge.indexing.*'`

Expected: FAIL。

- [ ] **Step 3: 实现文件存储和 Parser**

`FileSystemKnowledgeDocumentStore` 只接受由 UUID 组成的 Key，解析后必须满足
`resolved.startsWith(root)`。Parser 复用简历模块的 Tika 限制，允许
`text/markdown`、`text/plain`、PDF 和 DOCX，禁用 EmbeddedDocumentExtractor。

- [ ] **Step 4: 迁移 RecursiveTextSplitter**

迁移 InterviewGuide 中按标题、段落、行、中文/英文标点递归切分的实现，构造函数只接收
`maxSize=1600` 和 `overlapSize=200`，公开接口为：

```java
public List<String> split(String text) {
  if (text == null || text.isBlank()) return List.of();
  List<String> chunks = new ArrayList<>();
  splitRecursively(normalizeLineEndings(text).strip(), 0, chunks);
  return addOverlap(chunks);
}
```

- [ ] **Step 5: 运行测试**

Run: `.\gradlew.bat test --tests 'interview.pilot.knowledge.storage.*' --tests 'interview.pilot.knowledge.indexing.*'`

Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/interview/pilot/knowledge/storage src/main/java/interview/pilot/knowledge/indexing src/test/java/interview/pilot/knowledge
git commit -m "feat: add private knowledge document processing"
```

### Task 4: 上传接口和可靠索引任务

**Files:**
- Modify: `src/main/java/interview/pilot/async/domain/AsyncTaskType.java`
- Modify: `src/main/java/interview/pilot/async/messaging/RabbitTopologyConfig.java`
- Modify: `src/main/java/interview/pilot/async/application/AsyncTaskService.java`
- Create: `src/main/java/interview/pilot/knowledge/api/KnowledgeBaseController.java`
- Create: `src/main/java/interview/pilot/knowledge/api/KnowledgeBaseResponse.java`
- Create: `src/main/java/interview/pilot/knowledge/api/KnowledgeDocumentResponse.java`
- Create: `src/main/java/interview/pilot/knowledge/application/KnowledgeBaseService.java`
- Create: `src/main/java/interview/pilot/knowledge/application/KnowledgeDocumentUploadService.java`
- Create: `src/main/java/interview/pilot/knowledge/indexing/KnowledgeIndexListener.java`
- Create: `src/main/java/interview/pilot/knowledge/indexing/KnowledgeIndexer.java`
- Test: `src/test/java/interview/pilot/knowledge/api/KnowledgeBaseControllerTest.java`
- Test: `src/test/java/interview/pilot/knowledge/indexing/KnowledgeIndexListenerIT.java`

**Interfaces:**
- Produces: 设计规格中的知识库 CRUD、文档上传、查询、重建和删除接口。
- Produces: `KNOWLEDGE_DOCUMENT_INDEX` 和 `KNOWLEDGE_DOCUMENT_DELETE` Task Route。

- [ ] **Step 1: 写失败的 Controller 和 Listener 测试**

```java
@Test
void uploadCreatesProcessingDocumentAndDurableTask() throws Exception {
  mvc.perform(multipart("/api/knowledge-bases/{id}/documents", baseId)
      .file(mdFile).with(user(principal)).with(csrf()))
      .andExpect(status().isAccepted())
      .andExpect(jsonPath("$.status").value("PROCESSING"));
  assertThat(tasks.findByTaskTypeAndBizKey(
      KNOWLEDGE_DOCUMENT_INDEX, "knowledge-document:" + documentId)).isPresent();
}
```

Listener IT 覆盖成功进入 `READY`、临时错误重试、最终失败、重复投递和迟到 revision。

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests interview.pilot.knowledge.api.KnowledgeBaseControllerTest --tests interview.pilot.knowledge.indexing.KnowledgeIndexListenerIT`

Expected: FAIL。

- [ ] **Step 3: 实现上传短事务**

上传顺序固定为：校验拥有者 → 校验文件 → 保存原文件 → 短事务创建 Document 和 Task。
如果数据库事务失败，调用 Store 删除刚保存的孤儿文件。Task Payload 只保存
`documentId` 和 `indexRevision`，不把完整文档文本放入消息。

- [ ] **Step 4: 扩展 Rabbit Route 和重试**

`RabbitTopologyConfig.routeFor` 为两个知识任务返回独立 Exchange、Main Queue、Retry Queue 和 DLQ。`AsyncTaskService.retry` 按任务类型重置文档状态和索引版本，并验证任务属于当前用户。

- [ ] **Step 5: 实现 Listener 和 Indexer**

`KnowledgeIndexListener` 沿用现有消息重试模板；`KnowledgeIndexer` 在事务外执行打开文件、解析、切片、分批 Embedding/Qdrant Upsert，最后通过短事务 `markReady(expectedRevision, ...)`。

- [ ] **Step 6: 运行测试**

Run: `.\gradlew.bat test --tests 'interview.pilot.knowledge.*' --tests 'interview.pilot.async.*'`

Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/interview/pilot/knowledge src/main/java/interview/pilot/async src/test/java/interview/pilot/knowledge src/test/java/interview/pilot/async
git commit -m "feat: index knowledge documents asynchronously"
```

### Task 5: Qdrant Adapter 和强制租户过滤检索

**Files:**
- Create: `src/main/java/interview/pilot/knowledge/retrieval/KnowledgeChunk.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/RetrievalIntent.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/RetrievalStatus.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/RetrievedKnowledge.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/ValidatedKnowledgeScope.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/KnowledgeScopeResolver.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/KnowledgeRetriever.java`
- Create: `src/main/java/interview/pilot/knowledge/retrieval/QdrantKnowledgeRetriever.java`
- Test: `src/test/java/interview/pilot/knowledge/retrieval/KnowledgeScopeResolverTest.java`
- Test: `src/test/java/interview/pilot/knowledge/retrieval/QdrantKnowledgeRetrieverIT.java`

**Interfaces:**
- Produces:

```java
public interface KnowledgeRetriever {
  RetrievedKnowledge retrieve(ValidatedKnowledgeScope scope, RetrievalIntent intent);
}
```

- Produces: `KnowledgeScopeResolver.resolveForCreation(CurrentUser, List<UUID>)`。
- Produces: `KnowledgeScopeResolver.resolveForSession(CurrentUser, String scopeSnapshot)`，只接受已持久化会话快照。

- [ ] **Step 1: 写范围解析和真实 Qdrant 失败测试**

```java
@Test
void rejectsWhenAnyRequestedBaseBelongsToAnotherUser() {
  assertThatThrownBy(() -> resolver.resolveForCreation(
      userA, List.of(ownedBase, foreignBase)))
      .isInstanceOf(BusinessException.class)
      .extracting("code").isEqualTo("KNOWLEDGE_BASE_NOT_FOUND");
}
```

Qdrant IT 写入用户 A、B 的同主题片段，用 A 的范围检索后断言结果全部属于 A；再模拟非法过滤字段并断言返回 `UNAVAILABLE`，验证没有第二次无过滤调用。

- [ ] **Step 2: 运行并确认失败**

Run: `.\gradlew.bat test --tests 'interview.pilot.knowledge.retrieval.*'`

Expected: FAIL。

- [ ] **Step 3: 实现不可伪造的范围类型**

```java
public record ValidatedKnowledgeScope(
    UUID userId,
    List<UUID> knowledgeBaseIds,
    List<DocumentRevision> documents,
    String embeddingVersion) {
  public ValidatedKnowledgeScope {
    knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    documents = List.copyOf(documents);
    if (documents.isEmpty()) throw new IllegalArgumentException("documents must not be empty");
  }
  public record DocumentRevision(UUID documentId, int indexRevision) {}
}
```

构造器保持包可见，只有 `KnowledgeScopeResolver` 位于同包并创建实例。

- [ ] **Step 4: 实现强制 Filter.Expression**

检索过滤必须等价于：

```text
user_id == scope.userId
AND knowledge_base_id IN scope.knowledgeBaseIds
AND (
  (document_id == d1 AND index_revision == r1)
  OR (document_id == d2 AND index_revision == r2)
)
```

使用 `FilterExpressionBuilder` 构造，不拼接来自客户端的字符串。任何异常返回
`RetrievedKnowledge.unavailable(reason)`，方法内没有第二次 `similaritySearch`。

- [ ] **Step 5: 实现去重和上下文预算**

取 Top 12，按分数降序；同一文档优先保留不相邻片段；规范化空白后内容相同只保留一份；最终最多 6 个片段，总字符数不得超过配置值。

- [ ] **Step 6: 运行阶段测试和静态检查**

Run: `.\gradlew.bat test --tests 'interview.pilot.knowledge.*'; git diff --check`

Expected: PASS，diff check 无输出。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/interview/pilot/knowledge/retrieval src/test/java/interview/pilot/knowledge/retrieval
git commit -m "feat: add tenant-safe knowledge retrieval"
```
