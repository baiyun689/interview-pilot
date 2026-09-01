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
public class InterviewPreparationRetryPolicy extends AbstractRetryableTaskPolicy {
  private static final String BIZ_KEY_PREFIX = "interview:";
  private static final String CLAIM_KEY_PREFIX = "interview-preparation:";

  private final InterviewSessionRepository sessions;

  public InterviewPreparationRetryPolicy(InterviewSessionRepository sessions) {
    this.sessions = sessions;
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
        sessions.findBySessionIdAndUserAccountId(sessionId, userAccountId)
            .orElseThrow(AbstractRetryableTaskPolicy::stateInvalid);
    if (session.getStatus() != SessionStatus.PREPARATION_FAILED) {
      throw stateInvalid();
    }
    session.retryPreparation();
  }

  private UUID parseInterviewId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith(BIZ_KEY_PREFIX)) {
        throw new IllegalArgumentException();
      }
      return UUID.fromString(bizKey.substring(BIZ_KEY_PREFIX.length()));
    } catch (IllegalArgumentException exception) {
      throw stateInvalid();
    }
  }
}
