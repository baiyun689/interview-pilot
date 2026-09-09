package interview.pilot.async.policy;

import org.springframework.stereotype.Component;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.policy.RetryableTaskPolicy;
import interview.pilot.recruitment.application.HiringAccess;

/** Personal task ownership cannot substitute for current organization authorization. */
@Component
public class HiringWorkRetryPolicy implements RetryableTaskPolicy {
  @Override public AsyncTaskType type() { return AsyncTaskType.HIRING_WORK; }
  @Override public String claimKey(AsyncTaskEntity task) { throw HiringAccess.conflict("请在企业投递页面重试，以校验当前企业权限"); }
  @Override public void reset(AsyncTaskEntity task, long userAccountId) { throw HiringAccess.conflict("请在企业投递页面重试"); }
}
