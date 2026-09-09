package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.HiringAccess.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;
import interview.pilot.knowledge.retrieval.*;
import interview.pilot.recruitment.infrastructure.HiringStore;
import interview.pilot.recruitment.infrastructure.AssessmentEntities.Work;
import tools.jackson.databind.ObjectMapper;

@Service
public class EnterpriseKnowledgeService {
  private final HiringStore store;
  private final HiringAccess access;
  private final OrganizationService organizations;
  private final KnowledgeDocumentStore files;
  private final KnowledgeProperties properties;
  private final KnowledgeRetriever retriever;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final String embedding;
  public EnterpriseKnowledgeService(HiringStore store, HiringAccess access, OrganizationService organizations,
      KnowledgeDocumentStore files, KnowledgeProperties properties, KnowledgeRetriever retriever,
      PlatformTransactionManager manager, ObjectMapper json,
      @Value("${app.knowledge.embedding.model:text-embedding-v3}") String embedding) {
    this.store = store; this.access = access; this.organizations = organizations; this.files = files;
    this.properties = properties; this.retriever = retriever; this.json = json; this.embedding = embedding;
    this.tx = new TransactionTemplate(manager); this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  public record BaseView(UUID id, String name) {}
  public record DocumentView(UUID id, String filename, String status, int revision, int activeRevision,
      int chunkCount, String error, String deletionStatus) {}

  public List<BaseView> bases(CurrentUser user, Long orgId) {
    return tx.execute(s -> { authorize(user, orgId, false); return store.list(KnowledgeBaseEntity.class,
        "from KnowledgeBaseEntity where organizationId=?1 and status=interview.pilot.knowledge.domain.KnowledgeBaseStatus.ACTIVE order by id desc", 0, 100, orgId)
        .stream().map(b -> new BaseView(b.getKnowledgeBaseId(), b.getName())).toList(); });
  }
  public BaseView create(CurrentUser user, Long orgId, String name) {
    return tx.execute(s -> { authorize(user, orgId, true); var base = store.add(KnowledgeBaseEntity.forOrganization(orgId, name.trim()));
      organizations.audit(user, orgId, "KNOWLEDGE_BASE_CREATED", base.getId()); return new BaseView(base.getKnowledgeBaseId(), base.getName()); });
  }
  public List<DocumentView> documents(CurrentUser user, Long orgId, UUID baseId) {
    return tx.execute(s -> { var base = base(user, orgId, baseId, false); return store.list(KnowledgeDocumentEntity.class,
        "from KnowledgeDocumentEntity where knowledgeBase.id=?1 and status<>interview.pilot.knowledge.domain.KnowledgeDocumentStatus.DELETED order by id desc", 0, 200, base.getId())
        .stream().map(this::view).toList(); });
  }
  public DocumentView upload(CurrentUser user, Long orgId, UUID baseId, MultipartFile file) {
    if (!properties.enabled()) throw conflict("知识库索引尚未配置");
    if (file == null || file.isEmpty() || file.getSize() > 8L * 1024 * 1024) throw conflict("请选择不超过 8 MiB 的文件");
    String filename = file.getOriginalFilename();
    if (filename == null || filename.length() > 255 || !filename.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(pdf|txt|md|docx)$")) throw conflict("支持 PDF、TXT、Markdown 和 DOCX 文件");
    tx.executeWithoutResult(s -> base(user, orgId, baseId, false));
    String hash;
    try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes())); }
    catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("文件读取失败", e); }
    // Namespace is a storage directory key, not a synthetic user account or authorization identity.
    UUID namespace = UUID.nameUUIDFromBytes(("organization-files:" + orgId).getBytes(StandardCharsets.UTF_8));
    String key = files.store(namespace, UUID.randomUUID(), file);
    try {
      return tx.execute(s -> {
        var base = base(user, orgId, baseId, true);
        var existing = store.one(KnowledgeDocumentEntity.class, "from KnowledgeDocumentEntity where knowledgeBase.id=?1 and contentHash=?2", base.getId(), hash);
        if (existing.isPresent()) throw conflict("此文件已存在，请重建索引或上传不同内容");
        var document = store.add(KnowledgeDocumentEntity.pending(base, filename, hash, key));
        document.beginReindex(); enqueueIndex(user, document);
        organizations.audit(user, orgId, "KNOWLEDGE_DOCUMENT_UPLOADED", document.getId()); return view(document);
      });
    } catch (RuntimeException failure) { files.delete(key); throw failure; }
  }
  public DocumentView reindex(CurrentUser user, Long orgId, UUID baseId, UUID documentId) {
    if (!properties.enabled()) throw conflict("知识库索引尚未配置");
    return tx.execute(s -> {
      var document = document(user, orgId, baseId, documentId);
      if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING || document.getStatus() == KnowledgeDocumentStatus.DELETING || document.getStatus() == KnowledgeDocumentStatus.DELETED) throw conflict("当前文档状态不能重建索引");
      document.beginReindex(); enqueueIndex(user, document);
      organizations.audit(user, orgId, "KNOWLEDGE_DOCUMENT_REINDEXED", document.getId()); return view(document);
    });
  }
  public DocumentView delete(CurrentUser user, Long orgId, UUID baseId, UUID documentId) {
    return tx.execute(s -> {
      var document = document(user, orgId, baseId, documentId);
      if (document.getStatus() == KnowledgeDocumentStatus.DELETED) return view(document);
      if (document.getStatus() == KnowledgeDocumentStatus.PROCESSING) throw conflict("文档正在索引，请稍后删除");
      long references = store.one(Long.class,
          "select count(r) from HiringKnowledgeReference r where documentId=?1 and (expiresAt is null or expiresAt>?2)", documentId, Instant.now()).orElse(0L);
      if (references > 0) throw conflict("文档被已发布面试引用，保留期内不能删除");
      // Stable task business key provides identity without relying on matching arbitrary JSON fields.
      var taskReference = store.one(AsyncTaskEntity.class, "from AsyncTaskEntity where taskType=?1 and bizKey=?2", AsyncTaskType.HIRING_WORK, "hiring:delete:" + documentId);
      if (taskReference.isPresent()) {
        var work = store.one(Work.class, "from HiringWork where taskId=?1", taskReference.get().getId()).orElseThrow();
        work = store.find(Work.class, work.id, true).orElseThrow();
        if (work.status.equals("FAILED") || work.status.equals("CANCELLED")) {
          var task = store.find(AsyncTaskEntity.class, work.taskId, true).orElseThrow();
          work.status = "PENDING"; work.attempts = 0; work.error = null; work.nextAttemptAt = Instant.now();
          task.setStatus(AsyncTaskStatus.PENDING); task.setExecutionEpoch(task.getExecutionEpoch() + 1); task.setLastPublishedAt(null); task.setLastError(null);
        }
      } else {
        document.beginDeletion();
        var task = store.add(AsyncTaskEntity.pending(user.databaseId(), AsyncTaskType.HIRING_WORK, "hiring:delete:" + documentId, "{}"));
        var work = new Work(); work.organizationId = orgId; work.taskId = task.getId(); work.kind = "KNOWLEDGE_DELETE"; work.status = "PENDING";
        work.inputSnapshot = json.writeValueAsString(new EnterpriseKnowledgeDeleteProcessor.Input(document.getId(), documentId, document.getIndexRevision(), document.getStorageKey()));
        work.createdAt = Instant.now(); work.nextAttemptAt = work.createdAt; store.add(work);
      }
      organizations.audit(user, orgId, "KNOWLEDGE_DOCUMENT_DELETE_REQUESTED", document.getId()); return view(document);
    });
  }

  public ValidatedKnowledgeScope scope(CurrentUser user, Long orgId, List<UUID> baseIds) {
    return tx.execute(s -> {
      authorize(user, orgId, true);
      if (baseIds == null || baseIds.isEmpty() || baseIds.size() > 5) throw conflict("请选择 1 到 5 个企业知识库");
      var ids = baseIds.stream().distinct().toList();
      for (UUID id : ids) base(user, orgId, id, false);
      var documents = store.list(KnowledgeDocumentEntity.class,
          "from KnowledgeDocumentEntity where knowledgeBase.organizationId=?1 and knowledgeBase.knowledgeBaseId in ?2 and status=interview.pilot.knowledge.domain.KnowledgeDocumentStatus.READY order by id", 0, 1001, orgId, ids);
      if (documents.isEmpty()) throw conflict("所选知识库还没有可检索的文档");
      if (documents.size() > 1000) throw conflict("所选文档超过 1000 份，请缩小知识范围");
      // Hold document rows until the caller's publishing transaction registers revision pins.
      for (var document : documents) {
        var current = store.find(KnowledgeDocumentEntity.class, document.getId(), true).orElseThrow();
        if (current.getStatus() != KnowledgeDocumentStatus.READY) throw conflict("资料索引状态已变化，请稍后重试");
      }
      return new ValidatedKnowledgeScope(null, ids, documents.stream().map(d -> new ValidatedKnowledgeScope.DocumentRevision(d.getDocumentId(), d.getActiveIndexRevision())).toList(), embedding, orgId);
    });
  }
  public RetrievedKnowledge search(CurrentUser user, Long orgId, List<UUID> baseIds, String query) {
    var scope = scope(user, orgId, baseIds);
    return retriever.retrieve(scope, new RetrievalIntent(query, "", "MEDIUM", List.of(query), List.of(), 5, 24, .2, 6000));
  }
  private KnowledgeBaseEntity base(CurrentUser user, Long orgId, UUID id, boolean write) {
    authorize(user, orgId, write);
    return store.one(KnowledgeBaseEntity.class, "from KnowledgeBaseEntity where knowledgeBaseId=?1 and organizationId=?2", id, orgId).orElseThrow(HiringAccess::notFound);
  }
  private KnowledgeDocumentEntity document(CurrentUser user, Long orgId, UUID baseId, UUID id) {
    var base = base(user, orgId, baseId, true);
    var reference = store.one(KnowledgeDocumentEntity.class, "from KnowledgeDocumentEntity where documentId=?1 and knowledgeBase.id=?2", id, base.getId()).orElseThrow(HiringAccess::notFound);
    return store.find(KnowledgeDocumentEntity.class, reference.getId(), true).orElseThrow();
  }
  private void authorize(CurrentUser user, Long orgId, boolean write) {
    if (access.member(user, orgId, write).role.equals("INTERVIEWER")) throw forbidden();
  }
  private void enqueueIndex(CurrentUser user, KnowledgeDocumentEntity document) {
    String key = "knowledge-document:" + document.getDocumentId();
    var existing = store.one(AsyncTaskEntity.class, "from AsyncTaskEntity where taskType=?1 and bizKey=?2", AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, key);
    String payload = json.writeValueAsString(java.util.Map.of("documentId", document.getDocumentId(), "indexRevision", document.getIndexRevision()));
    if (existing.isEmpty()) store.add(AsyncTaskEntity.pending(user.databaseId(), AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, key, payload));
    else {
      var task = store.find(AsyncTaskEntity.class, existing.get().getId(), true).orElseThrow();
      task.setStatus(AsyncTaskStatus.PENDING); task.setExecutionEpoch(task.getExecutionEpoch() + 1);
      task.setAttemptCount(0); task.setLastPublishedAt(null); task.setLastError(null); task.setPayloadSnapshot(payload);
    }
  }
  private DocumentView view(KnowledgeDocumentEntity document) {
    String deletionStatus = null;
    if (document.getStatus() == KnowledgeDocumentStatus.DELETING) {
      var task = store.one(AsyncTaskEntity.class, "from AsyncTaskEntity where taskType=?1 and bizKey=?2", AsyncTaskType.HIRING_WORK, "hiring:delete:" + document.getDocumentId());
      deletionStatus = task.flatMap(t -> store.one(Work.class, "from HiringWork where taskId=?1", t.getId())).map(w -> w.status).orElse("PENDING");
    }
    return new DocumentView(document.getDocumentId(), document.getOriginalFilename(), document.getStatus().name(), document.getIndexRevision(), document.getActiveIndexRevision(), document.getChunkCount(), document.getFailureReason(), deletionStatus);
  }
}
