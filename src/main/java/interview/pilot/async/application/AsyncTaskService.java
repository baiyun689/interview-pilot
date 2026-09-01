package interview.pilot.async.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.api.AsyncTaskResponse;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.domain.KnowledgeDocumentStatus;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeRepository;

@Service
public class AsyncTaskService {
  private static final Logger log = LoggerFactory.getLogger(AsyncTaskService.class);

  private final AsyncTaskRepository tasks;
  private final ResumeRepository resumes;
  private final InterviewSessionRepository sessions;
  private final KnowledgeDocumentRepository knowledgeDocuments;
  private final ProcessingClaim claims;
  private final TransactionTemplate transactions;
  private final AiMetrics metrics;

  public AsyncTaskService(
      AsyncTaskRepository tasks,
      ResumeRepository resumes,
      InterviewSessionRepository sessions,
      KnowledgeDocumentRepository knowledgeDocuments,
      ProcessingClaim claims,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.tasks = tasks;
    this.resumes = resumes;
    this.sessions = sessions;
    this.knowledgeDocuments = knowledgeDocuments;
    this.claims = claims;
    this.transactions = new TransactionTemplate(transactionManager);
    this.metrics = metrics;
  }

  public AsyncTaskResponse get(CurrentUser user, UUID taskId) {
    return transactions.execute(status -> response(requireTask(user, taskId)));
  }

  public AsyncTaskResponse retry(CurrentUser user, UUID taskId, UUID traceId) {
    RetryTarget target = transactions.execute(status -> retryTarget(user, taskId));
    ProcessingClaim.ClearResult cleared;
    try {
      cleared = claims.clearTerminal(target.claimKey());
    } catch (RuntimeException exception) {
      throw conflict("TASK_RETRY_UNAVAILABLE", "Task retry is temporarily unavailable");
    }
    if (cleared == ProcessingClaim.ClearResult.ACTIVE) {
      throw conflict("TASK_STILL_PROCESSING", "Task processing is still active");
    }
    try {
      AsyncTaskResponse result = transactions.execute(status -> reset(target));
      log.info("async_task_retry taskId={} taskType={} traceId={}",
          result.taskId(), result.taskType(), traceId);
      return result;
    } catch (OptimisticLockingFailureException exception) {
      metrics.optimisticLockConflict();
      throw conflict("TASK_RETRY_CONFLICT", "Task retry conflicted with another request");
    }
  }

  private RetryTarget retryTarget(CurrentUser user, UUID taskId) {
    AsyncTaskEntity task = requireTask(user, taskId);
    requireRetryable(task);
    return new RetryTarget(task.getId(), requireOwner(user), task.getVersion(), claimKey(task));
  }

  private AsyncTaskResponse reset(RetryTarget target) {
    AsyncTaskEntity task = tasks.findByIdAndUserAccountId(target.databaseId(), target.userAccountId())
        .orElseThrow(() -> notFound());
    if (task.getVersion() != target.version()) {
      metrics.optimisticLockConflict();
      throw conflict("TASK_RETRY_CONFLICT", "Task retry conflicted with another request");
    }
    requireRetryable(task);
    if (task.getTaskType() == AsyncTaskType.RESUME_ANALYSIS) {
      Long resumeId = parseResumeId(task.getBizKey());
      var resume = resumes.findByIdAndUserAccountId(resumeId, target.userAccountId())
          .orElseThrow(() -> conflict("TASK_STATE_INVALID", "Task state is inconsistent"));
      if (resume.getStatus() != ResumeStatus.FAILED) {
        throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
      }
      resume.setStatus(ResumeStatus.PENDING);
      resume.setFailureReason(null);
      resume.setSkillsSnapshot(null);
      resume.setEvaluationSnapshot(null);
    } else if (task.getTaskType() == AsyncTaskType.KNOWLEDGE_DOCUMENT_INDEX
        || task.getTaskType() == AsyncTaskType.KNOWLEDGE_DOCUMENT_DELETE) {
      UUID documentId = parseKnowledgeDocumentId(task.getBizKey());
      var document = knowledgeDocuments.findByDocumentId(documentId)
          .orElseThrow(() -> conflict("TASK_STATE_INVALID", "Task state is inconsistent"));
      if (document.getStatus() != KnowledgeDocumentStatus.FAILED) {
        throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
      }
      document.beginReindex();
    } else if (task.getTaskType() == AsyncTaskType.INTERVIEW_QUESTION_PREPARATION) {
      UUID sessionId = parseInterviewId(task.getBizKey());
      var session = sessions.findBySessionIdAndUserAccountId(sessionId, target.userAccountId())
          .orElseThrow(() -> conflict("TASK_STATE_INVALID", "Task state is inconsistent"));
      if (session.getStatus() != SessionStatus.PREPARATION_FAILED) {
        throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
      }
      session.retryPreparation();
    } else if (task.getTaskType() == AsyncTaskType.VOICE_TRANSCRIPTION) {
      // Voice transcription retry is owned by VoiceAnswerModule.retry, which resets the
      // recording row and the task row in lockstep (V9 fenced epoch). The generic endpoint
      // must not reset the task alone. Task R owns the policy registry refactor.
      throw conflict("TASK_NOT_RETRYABLE", "Voice transcription retry is managed by the recording");
    } else {
      UUID sessionId = parseInterviewId(task.getBizKey());
      var session = sessions.findBySessionIdAndUserAccountId(sessionId, target.userAccountId())
          .orElseThrow(() -> conflict("TASK_STATE_INVALID", "Task state is inconsistent"));
      if (session.getStatus() != SessionStatus.EVALUATION_FAILED) {
        throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
      }
      session.retryEvaluation();
    }
    task.setStatus(AsyncTaskStatus.PENDING);
    task.setExecutionEpoch(task.getExecutionEpoch() + 1);
    task.setLastPublishedAt(null);
    task.setLastError(null);
    return response(tasks.saveAndFlush(task));
  }

  private void requireRetryable(AsyncTaskEntity task) {
    if (task.getStatus() != AsyncTaskStatus.FAILED
        && task.getStatus() != AsyncTaskStatus.DEAD) {
      throw conflict("TASK_NOT_RETRYABLE", "Only failed or dead tasks can be retried");
    }
  }

  private String claimKey(AsyncTaskEntity task) {
    return switch (task.getTaskType()) {
      case RESUME_ANALYSIS -> "resume-analysis:" + parseResumeId(task.getBizKey());
      case INTERVIEW_QUESTION_PREPARATION ->
          "interview-preparation:" + parseInterviewId(task.getBizKey());
      case INTERVIEW_EVALUATION -> "interview-report:" + parseInterviewId(task.getBizKey());
      case KNOWLEDGE_DOCUMENT_INDEX, KNOWLEDGE_DOCUMENT_DELETE ->
          "knowledge-index:" + parseKnowledgeDocumentId(task.getBizKey());
      case VOICE_TRANSCRIPTION -> "voice-recording:" + parseVoiceRecordingId(task.getBizKey());
    };
  }

  private Long parseResumeId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith("resume:")) throw new IllegalArgumentException();
      return Long.valueOf(bizKey.substring("resume:".length()));
    } catch (IllegalArgumentException exception) {
      throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
    }
  }

  private UUID parseInterviewId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith("interview:")) throw new IllegalArgumentException();
      return UUID.fromString(bizKey.substring("interview:".length()));
    } catch (IllegalArgumentException exception) {
      throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
    }
  }

  private UUID parseKnowledgeDocumentId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith("knowledge-document:"))
        throw new IllegalArgumentException();
      return UUID.fromString(bizKey.substring("knowledge-document:".length()));
    } catch (IllegalArgumentException exception) {
      throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
    }
  }

  private UUID parseVoiceRecordingId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith("voice-recording:"))
        throw new IllegalArgumentException();
      return UUID.fromString(bizKey.substring("voice-recording:".length()));
    } catch (IllegalArgumentException exception) {
      throw conflict("TASK_STATE_INVALID", "Task state is inconsistent");
    }
  }

  private AsyncTaskEntity requireTask(CurrentUser user, UUID taskId) {
    return tasks.findByTaskIdAndUserAccountId(taskId, requireOwner(user)).orElseThrow(this::notFound);
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private AsyncTaskResponse response(AsyncTaskEntity task) {
    return new AsyncTaskResponse(
        task.getTaskId(), task.getTaskType(), task.getStatus(), task.getAttemptCount(),
        task.getPublishAttempts(), task.getLastError(), task.getCreatedAt(), task.getUpdatedAt());
  }

  private BusinessException notFound() {
    return new BusinessException("TASK_NOT_FOUND", "Async task not found", HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private record RetryTarget(Long databaseId, Long userAccountId, long version, String claimKey) {}
}
