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
    this.metrics = metrics;
  }

  public Outcome handle(TaskMessage message) {
    BeginResult begin = transactions.execute(status -> beginMessage(message));
    return begin.stale() ? Outcome.STALE : index(begin.work());
  }

  private Outcome index(IndexWork work) {
    if (work == null) return Outcome.TERMINAL;
    try {
      int chunkCount = indexer.index(work.documentUuid(), work.indexRevision());
      if (chunkCount < 0) {
        return transactions.execute(status -> fail(work));
      }
      return transactions.execute(status -> complete(work, chunkCount));
    } catch (RuntimeException exception) {
      boolean current = Boolean.TRUE.equals(
          transactions.execute(status -> recordRetryableFailure(work)));
      if (!current) return Outcome.STALE;
      throw new KnowledgeIndexRetryableException(work.attemptGeneration());
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

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      KnowledgeDocumentEntity document = requireDocument(task);
      return markDead(task, document, attemptGeneration, message.executionEpoch());
    }));
  }

  public boolean markDeadCurrent(TaskMessage message) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      AsyncTaskEntity task = requireMatchingTask(message);
      KnowledgeDocumentEntity document = requireDocument(task);
      return markDead(task, document, task.getAttemptCount(), message.executionEpoch());
    }));
  }

  private BeginResult beginMessage(TaskMessage message) {
    AsyncTaskEntity task = requireMatchingTask(message);
    if (task.getExecutionEpoch() != message.executionEpoch()) {
      return new BeginResult(null, true);
    }
    return new BeginResult(begin(task), false);
  }

  private IndexWork begin(AsyncTaskEntity task) {
    KnowledgeDocumentEntity document = requireDocument(task);
    if (deleting(document)) {
      return null;
    }
    if (task.getStatus() == AsyncTaskStatus.COMPLETED
        && document.getStatus() == KnowledgeDocumentStatus.READY) {
      return null;
    }
    if ((task.getStatus() == AsyncTaskStatus.FAILED || task.getStatus() == AsyncTaskStatus.DEAD)
        && document.getStatus() == KnowledgeDocumentStatus.FAILED) {
      return null;
    }
    if (document.getStatus() != KnowledgeDocumentStatus.PROCESSING) {
      throw new IllegalStateException("Knowledge document index state is inconsistent");
    }
    if (task.getStatus() == AsyncTaskStatus.PENDING) {
      task.setStatus(AsyncTaskStatus.PUBLISHED);
    } else if (task.getStatus() != AsyncTaskStatus.PUBLISHED) {
      throw new IllegalStateException("Knowledge index task state is inconsistent");
    }
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastError(null);
    return new IndexWork(
        task.getTaskId(), document.getId(), document.getDocumentId(),
        document.getIndexRevision(), task.getAttemptCount());
  }

  private Outcome complete(IndexWork work, int chunkCount) {
    AsyncTaskEntity task = requireTask(work.taskId());
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(work.documentUuid())
        .orElseThrow();
    if (!current(task, document, work)) return Outcome.STALE;
    document.markReady(work.indexRevision(), document.getParsedText(), chunkCount);
    documentRepository.save(document);
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.setLastError(null);
    metrics.afterCommit(() -> metrics.taskCompleted(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX));
    return Outcome.TERMINAL;
  }

  private Outcome fail(IndexWork work) {
    AsyncTaskEntity task = requireTask(work.taskId());
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(work.documentUuid())
        .orElseThrow();
    if (!current(task, document, work)) return Outcome.STALE;
    document.markFailed(work.indexRevision(), INVALID_OUTPUT_ERROR);
    documentRepository.save(document);
    task.setStatus(AsyncTaskStatus.FAILED);
    task.setLastError(INVALID_OUTPUT_ERROR);
    metrics.afterCommit(() -> metrics.taskFailed(AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX, "failed"));
    return Outcome.TERMINAL;
  }

  private boolean recordRetryableFailure(IndexWork work) {
    AsyncTaskEntity task = requireTask(work.taskId());
    KnowledgeDocumentEntity document = documentRepository.findByDocumentId(work.documentUuid())
        .orElseThrow();
    if (!current(task, document, work)) return false;
    task.setLastError(RETRYABLE_ERROR);
    return true;
  }

  private boolean current(AsyncTaskEntity task, KnowledgeDocumentEntity document, IndexWork work) {
    return task.getStatus() == AsyncTaskStatus.PUBLISHED
        && task.getAttemptCount() == work.attemptGeneration()
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
      int indexRevision, int attemptGeneration) {}

  private record BeginResult(IndexWork work, boolean stale) {}

  public enum Outcome { TERMINAL, STALE }

  public record IndexTarget(
      UUID documentUuid, boolean terminal, int attemptGeneration, int executionEpoch) {}
}
