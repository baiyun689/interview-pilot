package interview.pilot.knowledge.indexing;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentEntity;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeIndexHandler {
  static final String INVALID_OUTPUT_ERROR = "Knowledge document indexing produced invalid output";
  static final String RETRYABLE_ERROR = "Knowledge document indexing temporarily unavailable";
  static final String DEAD_ERROR = "Knowledge document index retries exhausted";

  @org.springframework.beans.factory.annotation.Value("${app.async.knowledge-index.lease-duration:2m}")
  private java.time.Duration leaseDuration = java.time.Duration.ofMinutes(2);
  private final KnowledgeIndexer indexer;
  private final KnowledgeDocumentRepository documentRepository;
  private final AsyncTaskRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;
  private final AiMetrics metrics;

  public KnowledgeIndexHandler(
      KnowledgeIndexer indexer,
      KnowledgeDocumentRepository documentRepository,
      AsyncTaskRepository taskRepository,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.indexer = indexer;
    this.documentRepository = documentRepository;
    this.taskRepository = taskRepository;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.metrics = metrics;
  }

  public Outcome handle(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> beginMessage(message));
    return begin.skipped() != null ? begin.skipped() : index(begin.work());
  }

  private Outcome index(IndexWork work) {
    if (work == null) return Outcome.TERMINAL;
    try {
      KnowledgeIndexer.IndexResult indexed = indexer.index(work.documentUuid(), work.indexRevision());
      if (indexed.chunkCount() < 0) {
        return transactions.execute(status -> fail(work));
      }
      return transactions.execute(status -> complete(work, indexed));
    } catch (RuntimeException exception) {
      boolean current = Boolean.TRUE.equals(
          transactions.execute(status -> recordRetryableFailure(work)));
      if (!current) return Outcome.STALE;
      throw new KnowledgeIndexRetryableException(work.attemptGeneration(), work.executionToken(), exception);
    }
  }

  public IndexTarget inspect(TaskMessage message) {
    return transactions.execute(status -> inspectInTransaction(message));
  }

  private IndexTarget inspectInTransaction(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Knowledge index message identity is invalid");
    }
    AsyncTaskEntity task = requireTask(message.taskId());
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Knowledge index message does not match stored task");
    }
    KnowledgeDocumentEntity document = requireDocument(task);
    if (message.executionEpoch() < task.getExecutionEpoch()) {
      return new IndexTarget(
          document.getDocumentId(), true, task.getAttemptCount(), task.getExecutionEpoch());
    }
    if (message.executionEpoch() != task.getExecutionEpoch()) {
      throw new IllegalArgumentException("Knowledge index message epoch is invalid");
    }
    if (deleting(document)) {
      return new IndexTarget(
          document.getDocumentId(), true, task.getAttemptCount(), task.getExecutionEpoch());
    }
    boolean succeeded = task.getStatus() == AsyncTaskStatus.COMPLETED
        && document.getStatus() == KnowledgeDocumentStatus.READY;
    boolean stopped = task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD;
    return new IndexTarget(
        document.getDocumentId(), succeeded || stopped,
        task.getAttemptCount(), task.getExecutionEpoch());
  }

  public boolean markDead(TaskMessage message, int generation, String token) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      taskRepository.findByTaskIdForUpdate(message.taskId()).orElseThrow();
      AsyncTaskEntity task = requireMatchingTask(message);
      if (!task.ownsExecution(message.executionEpoch(), generation, token)) return false;
      return markDead(task, requireDocument(task), generation, message.executionEpoch());
    }));
  }

  public void releaseForRetry(TaskMessage message, int generation, String token) {
    transactions.executeWithoutResult(status -> taskRepository.releaseExecution(message.taskId().toString(),
        AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX.name(), message.bizKey(), message.executionEpoch(), generation, token));
  }

  private BeginResult beginMessage(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    if (task.getExecutionEpoch() != message.executionEpoch() || task.getStatus() == AsyncTaskStatus.COMPLETED
        || task.getStatus() == AsyncTaskStatus.FAILED || task.getStatus() == AsyncTaskStatus.DEAD)
      return new BeginResult(null, Outcome.STALE);
    String token = UUID.randomUUID().toString();
    if (taskRepository.claimExecution(message.taskId().toString(), AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX.name(),
        message.bizKey(), message.executionEpoch(), token,
        interview.pilot.async.infrastructure.ExecutionLeaseDuration.seconds(leaseDuration)) != 1)
      return new BeginResult(null, Outcome.BUSY);
    task = requireMatchingTask(message);
    KnowledgeDocumentEntity document = requireDocument(task);
    if (deleting(document)) {
      task.setStatus(AsyncTaskStatus.COMPLETED);
      task.clearExecutionLease();
      return new BeginResult(null, Outcome.STALE);
    }
    if (document.getStatus() != KnowledgeDocumentStatus.PROCESSING)
      throw new IllegalStateException("Knowledge document index state is inconsistent");
    return new BeginResult(new IndexWork(task.getTaskId(), document.getId(), document.getDocumentId(),
        document.getIndexRevision(), task.getAttemptCount(), task.getExecutionEpoch(), token), null);
  }

  private Outcome complete(IndexWork work, KnowledgeIndexer.IndexResult indexed) {
    AsyncTaskEntity task = taskRepository.findByTaskIdForUpdate(work.taskId()).orElseThrow();
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(work.documentUuid())
        .orElseThrow();
    if (!current(task, document, work)) return Outcome.STALE;
    document.markReady(work.indexRevision(), indexed.parsedText(), indexed.chunkCount());
    document.setEmbeddingSnapshot(indexed.embeddingSnapshot());
    documentRepository.save(document);
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.clearExecutionLease();
    task.setLastError(null);
    metrics.afterCommit(() -> metrics.taskCompleted(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX));
    return Outcome.TERMINAL;
  }

  private Outcome fail(IndexWork work) {
    AsyncTaskEntity task = taskRepository.findByTaskIdForUpdate(work.taskId()).orElseThrow();
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(work.documentUuid())
        .orElseThrow();
    if (!current(task, document, work)) return Outcome.STALE;
    document.markFailed(work.indexRevision(), INVALID_OUTPUT_ERROR);
    documentRepository.save(document);
    task.setStatus(AsyncTaskStatus.FAILED);
    task.clearExecutionLease();
    task.setLastError(INVALID_OUTPUT_ERROR);
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "failed"));
    return Outcome.TERMINAL;
  }

  private boolean recordRetryableFailure(IndexWork work) {
    AsyncTaskEntity task = taskRepository.findByTaskIdForUpdate(work.taskId()).orElseThrow();
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(work.documentUuid())
        .orElseThrow();
    if (!current(task, document, work)) return false;
    task.setLastError(RETRYABLE_ERROR);
    return true;
  }

  private boolean current(AsyncTaskEntity task, KnowledgeDocumentEntity document, IndexWork work) {
    return task.ownsExecution(work.executionEpoch(), work.attemptGeneration(), work.executionToken())
        && document.getId().equals(work.documentId())
        && document.getStatus() == KnowledgeDocumentStatus.PROCESSING
        && document.getIndexRevision() == work.indexRevision();
  }

  private boolean deleting(KnowledgeDocumentEntity document) {
    return document.getStatus() == KnowledgeDocumentStatus.DELETING
        || document.getStatus() == KnowledgeDocumentStatus.DELETED;
  }

  private boolean markDead(
      AsyncTaskEntity task,
      KnowledgeDocumentEntity document,
      int expectedAttemptGeneration,
      int expectedExecutionEpoch) {
    if (task.getExecutionEpoch() != expectedExecutionEpoch
        || task.getAttemptCount() != expectedAttemptGeneration) {
      return false;
    }
    if (task.getStatus() == AsyncTaskStatus.DEAD
        && document.getStatus() == KnowledgeDocumentStatus.FAILED) return true;
    if ((task.getStatus() != AsyncTaskStatus.PENDING
        && task.getStatus() != AsyncTaskStatus.PUBLISHED)
        || (document.getStatus() != KnowledgeDocumentStatus.PENDING
        && document.getStatus() != KnowledgeDocumentStatus.PROCESSING)) {
      return false;
    }
    task.setStatus(AsyncTaskStatus.DEAD);
    task.clearExecutionLease();
    task.setLastError(DEAD_ERROR);
    document.markFailed(document.getIndexRevision(), DEAD_ERROR);
    documentRepository.save(document);
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "dead"));
    return true;
  }

  private AsyncTaskEntity requireTask(UUID taskId) {
    return taskRepository.findByTaskId(Objects.requireNonNull(taskId, "taskId"))
        .orElseThrow(() -> new IllegalArgumentException("Knowledge index task not found"));
  }

  private AsyncTaskEntity requireMatchingTask(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX
        || message.bizKey() == null) {
      throw new IllegalArgumentException("Knowledge index message identity is invalid");
    }
    AsyncTaskEntity task = requireTask(message.taskId());
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Knowledge index message does not match stored task");
    }
    return task;
  }

  private KnowledgeDocumentEntity requireDocument(AsyncTaskEntity task) {
    if (task.getTaskType() != AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX) {
      throw new IllegalArgumentException("Task is not a knowledge index task");
    }
    String prefix = "knowledge-document:";
    if (task.getBizKey() == null || !task.getBizKey().startsWith(prefix)) {
      throw new IllegalArgumentException("Knowledge index business key is invalid");
    }
    try {
      UUID documentId = UUID.fromString(task.getBizKey().substring(prefix.length()));
      return documentRepository.findByDocumentId(documentId)
          .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Knowledge index business key is invalid");
    }
  }

  private record IndexWork(
      UUID taskId, Long documentId, UUID documentUuid,
      int indexRevision, int attemptGeneration, int executionEpoch, String executionToken) {}

  private record BeginResult(IndexWork work, Outcome skipped) {}

  public enum Outcome { TERMINAL, STALE, BUSY }

  public record IndexTarget(
      UUID documentUuid, boolean terminal, int attemptGeneration, int executionEpoch) {}
}
