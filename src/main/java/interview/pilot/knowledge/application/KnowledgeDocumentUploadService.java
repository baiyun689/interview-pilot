package interview.pilot.knowledge.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.knowledge.api.KnowledgeDocumentResponse;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;

@Service
public class KnowledgeDocumentUploadService {
  private final KnowledgeBaseRepository baseRepository;
  private final KnowledgeDocumentRepository documentRepository;
  private final AsyncTaskRepository taskRepository;
  private final KnowledgeDocumentStore store;
  private final TransactionTemplate transactionTemplate;
  private final boolean knowledgeEnabled;

  public KnowledgeDocumentUploadService(
      KnowledgeBaseRepository baseRepository,
      KnowledgeDocumentRepository documentRepository,
      AsyncTaskRepository taskRepository,
      KnowledgeDocumentStore store,
      PlatformTransactionManager transactionManager,
      KnowledgeProperties properties) {
    this.baseRepository = baseRepository;
    this.documentRepository = documentRepository;
    this.taskRepository = taskRepository;
    this.store = store;
    this.knowledgeEnabled = properties.enabled();
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public KnowledgeDocumentResponse upload(
      CurrentUser user, UUID knowledgeBaseId, MultipartFile file) {
    ensureKnowledgeEnabled();
    Long userAccountId = requireOwner(user);
    baseRepository.findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, userAccountId)
        .orElseThrow(() -> new BusinessException(
            "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.NOT_FOUND));
    validateFile(file);

    UUID documentId = UUID.randomUUID();
    String storageKey = store.store(user.userId(), documentId, file);
    String contentHash = sha256(file);
    try {
      return Objects.requireNonNull(transactionTemplate.execute(status -> {
        KnowledgeBaseEntity base = baseRepository.findByKnowledgeBaseIdAndUserAccountId(
            knowledgeBaseId, userAccountId).orElseThrow();
        KnowledgeDocumentEntity document = KnowledgeDocumentEntity.pending(
            base, file.getOriginalFilename(), contentHash, storageKey);
        document.beginReindex();
        int indexRevision = document.getIndexRevision();
        document = documentRepository.save(document);

        String bizKey = "knowledge-document:" + document.getDocumentId();
        String payload = "{\"documentId\":\"" + document.getDocumentId()
            + "\",\"indexRevision\":" + indexRevision + "}";
        taskRepository.save(AsyncTaskEntity.pending(
            userAccountId, AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, bizKey, payload));

        return new KnowledgeDocumentResponse(
            document.getDocumentId(), document.getOriginalFilename(),
            document.getStatus().name(), indexRevision,
            document.getChunkCount(), null, document.getCreatedAt());
      }));
    } catch (RuntimeException exception) {
      store.delete(storageKey);
      throw exception;
    }
  }

  public List<KnowledgeDocumentResponse> listDocuments(CurrentUser user, UUID knowledgeBaseId) {
    Long userAccountId = requireOwner(user);
    baseRepository.findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, userAccountId)
        .orElseThrow(() -> new BusinessException(
            "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.NOT_FOUND));
    var docs = documentRepository.findReadyByKnowledgeBaseIdsAndUserAccountId(
        List.of(knowledgeBaseId), userAccountId);
    List<KnowledgeDocumentResponse> responses = new ArrayList<>();
    for (var doc : docs) {
      responses.add(new KnowledgeDocumentResponse(
          doc.getDocumentId(), doc.getOriginalFilename(), doc.getStatus().name(),
          doc.getIndexRevision(), doc.getChunkCount(),
          doc.getFailureReason(), doc.getCreatedAt()));
    }
    return List.copyOf(responses);
  }

  public KnowledgeDocumentResponse reindex(
      CurrentUser user, UUID knowledgeBaseId, UUID documentId) {
    ensureKnowledgeEnabled();
    Long userAccountId = requireOwner(user);
    baseRepository.findByKnowledgeBaseIdAndUserAccountId(knowledgeBaseId, userAccountId)
        .orElseThrow(() -> new BusinessException(
            "KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found", HttpStatus.NOT_FOUND));
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(documentId)
        .orElseThrow(() -> new BusinessException(
            "DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND));
    if (!document.getKnowledgeBase().getKnowledgeBaseId().equals(knowledgeBaseId)) {
      throw new BusinessException(
          "DOCUMENT_NOT_FOUND", "Knowledge document not found", HttpStatus.NOT_FOUND);
    }
    int revision = document.beginReindex();
    document = documentRepository.save(document);

    String bizKey = "knowledge-document:" + document.getDocumentId();
    String payload = "{\"documentId\":\"" + document.getDocumentId()
        + "\",\"indexRevision\":" + revision + "}";
    taskRepository.save(AsyncTaskEntity.pending(
        userAccountId, AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, bizKey, payload));

    return new KnowledgeDocumentResponse(
        document.getDocumentId(), document.getOriginalFilename(),
        document.getStatus().name(), revision, document.getChunkCount(), null,
        document.getCreatedAt());
  }

  private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(
          "FILE_REQUIRED", "Knowledge document file is required", HttpStatus.BAD_REQUEST);
    }
  }

  private void ensureKnowledgeEnabled() {
    if (!knowledgeEnabled) {
      throw new BusinessException(
          "KNOWLEDGE_DISABLED", "Knowledge base indexing is disabled",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private static Long requireOwner(CurrentUser user) {
    return Objects.requireNonNull(
        Objects.requireNonNull(user, "user").databaseId(), "user.databaseId");
  }

  private static String sha256(MultipartFile file) {
    try {
      byte[] fileBytes = file.getBytes();
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(fileBytes);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException | java.io.IOException exception) {
      throw new IllegalStateException("Could not compute document hash", exception);
    }
  }
}
