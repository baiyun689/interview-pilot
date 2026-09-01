package interview.pilot.async.policy;

import java.util.UUID;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;

/**
 * Manual retry for INTERVIEW_QUESTION_PREPARATION: the session must be
 * PREPARATION_FAILED before the task is reset, and the retry re-queues preparation.
 */
@Component
public class InterviewPreparationRetryPolicy extends AbstractInterviewSessionRetryPolicy {
  /**
   * Public so {@code QuestionPreparationListener} acquires the very key this policy clears
   * on manual retry — a drifted literal on either side would silently break retry
   * idempotency.
   */
  public static final String CLAIM_KEY_PREFIX = "interview-preparation:";

  public InterviewPreparationRetryPolicy(InterviewSessionRepository sessions) {
    super(sessions);
  }

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.INTERVIEW_QUESTION_PREPARATION;
  }

  @Override
  public String claimKey(AsyncTaskEntity task) {
    return CLAIM_KEY_PREFIX + parseInterviewId(task.getBizKey());
  }

  @Override
  public void reset(AsyncTaskEntity task, long userAccountId) {
    UUID sessionId = parseInterviewId(task.getBizKey());
    InterviewSessionEntity session =
        sessions().findBySessionIdAndUserAccountId(sessionId, userAccountId)
            .orElseThrow(AbstractRetryableTaskPolicy::stateInvalid);
    if (session.getStatus() != SessionStatus.PREPARATION_FAILED) {
      throw stateInvalid();
    }
    session.retryPreparation();
  }
}
