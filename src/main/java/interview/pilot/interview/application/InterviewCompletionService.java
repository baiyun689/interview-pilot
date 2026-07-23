package interview.pilot.interview.application;

import org.springframework.stereotype.Service;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;

/** Creates the durable report work item inside the transaction that completes interviewing. */
@Service
public class InterviewCompletionService {
  private final AsyncTaskRepository tasks;
  public InterviewCompletionService(AsyncTaskRepository tasks) {
    this.tasks = tasks;
  }

  public AsyncTaskEntity ensureReportTask(InterviewSessionEntity session) {
    if (session == null || session.getSessionId() == null
        || session.getStatus() != SessionStatus.EVALUATING) {
      throw new IllegalStateException("Interview must be evaluating before report work is created");
    }
    String bizKey = "interview:" + session.getSessionId();
    Long ownerId = session.getUserAccountId();
    if (ownerId == null) throw new IllegalStateException("Interview session owner is required");
    return tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, bizKey, ownerId)
        .orElseGet(() -> tasks.save(AsyncTaskEntity.pending(
            ownerId,
            AsyncTaskType.INTERVIEW_EVALUATION,
            bizKey,
            "{\"sessionId\":\"" + session.getSessionId() + "\"}")));
  }
}
