package interview.pilot.async.policy;

import java.util.UUID;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;

/**
 * Manual retry for INTERVIEW_EVALUATION: the session must be EVALUATION_FAILED before the
 * task is reset, and the retry re-queues evaluation.
 */
@Component
public class InterviewEvaluationRetryPolicy extends AbstractInterviewSessionRetryPolicy {
  /**
   * Public so {@code InterviewReportListener} acquires the very key this policy clears on
   * manual retry — a drifted literal on either side would silently break retry idempotency.
   */
  public static final String CLAIM_KEY_PREFIX = "interview-report:";

  public InterviewEvaluationRetryPolicy(InterviewSessionRepository sessions) {
    super(sessions);
  }

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.INTERVIEW_EVALUATION;
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
    if (session.getStatus() != SessionStatus.EVALUATION_FAILED) {
      throw stateInvalid();
    }
    session.retryEvaluation();
  }
}
