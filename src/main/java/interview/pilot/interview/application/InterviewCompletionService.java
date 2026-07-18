package interview.pilot.interview.application;

import org.springframework.stereotype.Service;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.infrastructure.LegacyUserAccountIdProvider;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;

/** Creates the durable report work item inside the transaction that completes interviewing. */
@Service
public class InterviewCompletionService {
  private final AsyncTaskRepository tasks;
  private final LegacyUserAccountIdProvider legacyOwner;

  public InterviewCompletionService(
      AsyncTaskRepository tasks,
      LegacyUserAccountIdProvider legacyOwner) {
    this.tasks = tasks;
    this.legacyOwner = legacyOwner;
  }

  public AsyncTaskEntity ensureReportTask(InterviewSessionEntity session) {
    if (session == null || session.getSessionId() == null
        || session.getStatus() != SessionStatus.EVALUATING) {
      throw new IllegalStateException("Interview must be evaluating before report work is created");
    }
    String bizKey = "interview:" + session.getSessionId();
    return tasks.findByTaskTypeAndBizKey(AsyncTaskType.INTERVIEW_EVALUATION, bizKey)
        .orElseGet(() -> tasks.save(AsyncTaskEntity.pending(
            legacyOwner.currentUserAccountId(),
            AsyncTaskType.INTERVIEW_EVALUATION,
            bizKey,
            "{\"sessionId\":\"" + session.getSessionId() + "\"}")));
  }
}
