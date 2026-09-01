package interview.pilot.async.policy;

import org.springframework.stereotype.Component;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;

/**
 * Manual retry for RESUME_ANALYSIS: the resume row must be FAILED before the task is reset,
 * and the retry re-queues analysis by moving the row back to PENDING and dropping the
 * failure evidence and any prior snapshots.
 */
@Component
public class ResumeAnalysisRetryPolicy extends AbstractRetryableTaskPolicy {
  private static final String BIZ_KEY_PREFIX = "resume:";
  /**
   * Public so {@code ResumeAnalysisListener} acquires the very key this policy clears on
   * manual retry — a drifted literal on either side would silently break retry idempotency.
   */
  public static final String CLAIM_KEY_PREFIX = "resume-analysis:";

  private final ResumeRepository resumes;

  public ResumeAnalysisRetryPolicy(ResumeRepository resumes) {
    this.resumes = resumes;
  }

  @Override
  public AsyncTaskType type() {
    return AsyncTaskType.RESUME_ANALYSIS;
  }

  @Override
  public String claimKey(AsyncTaskEntity task) {
    return CLAIM_KEY_PREFIX + parseResumeId(task.getBizKey());
  }

  @Override
  public void reset(AsyncTaskEntity task, long userAccountId) {
    Long resumeId = parseResumeId(task.getBizKey());
    ResumeEntity resume = resumes.findByIdAndUserAccountId(resumeId, userAccountId)
        .orElseThrow(AbstractRetryableTaskPolicy::stateInvalid);
    if (resume.getStatus() != ResumeStatus.FAILED) {
      throw stateInvalid();
    }
    resume.setStatus(ResumeStatus.PENDING);
    resume.setFailureReason(null);
    resume.setSkillsSnapshot(null);
    resume.setEvaluationSnapshot(null);
  }

  private Long parseResumeId(String bizKey) {
    try {
      if (bizKey == null || !bizKey.startsWith(BIZ_KEY_PREFIX)) {
        throw new IllegalArgumentException();
      }
      return Long.valueOf(bizKey.substring(BIZ_KEY_PREFIX.length()));
    } catch (IllegalArgumentException exception) {
      throw stateInvalid();
    }
  }
}
