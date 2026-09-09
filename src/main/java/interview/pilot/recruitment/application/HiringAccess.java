package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.recruitment.infrastructure.HiringStore;

@Component
public class HiringAccess {
  private final HiringStore store;
  public HiringAccess(HiringStore store) { this.store = store; }

  public Membership member(CurrentUser user, Long organizationId, boolean write) {
    // All organization mutations acquire this lock before checking membership, so disabling a
    // member and changing a protected resource have a defined order even across app instances.
    store.find(Organization.class, organizationId, write).filter(o -> o.active).orElseThrow(HiringAccess::notFound);
    return store.one(Membership.class,
        "from HiringMembership where organizationId=?1 and userAccountId=?2 and active=true",
        organizationId, user.databaseId()).orElseThrow(HiringAccess::notFound);
  }

  public void admin(CurrentUser user, Long organizationId, boolean write) {
    if (!member(user, organizationId, write).role.equals("ADMIN")) throw forbidden();
  }

  public Job job(CurrentUser user, Long organizationId, Long jobId, boolean write) {
    var member = member(user, organizationId, write);
    var job = store.find(Job.class, jobId, write)
        .filter(it -> it.organizationId.equals(organizationId)).orElseThrow(HiringAccess::notFound);
    if (!member.role.equals("ADMIN") && !assigned(jobId, user.databaseId())) throw notFound();
    if (write && member.role.equals("INTERVIEWER")) throw forbidden();
    return job;
  }

  public boolean assigned(Long jobId, Long userId) {
    return store.one(JobAssignment.class,
        "from HiringJobAssignment where jobId=?1 and userAccountId=?2", jobId, userId).isPresent();
  }

  public static BusinessException notFound() {
    return new BusinessException("HIRING_NOT_FOUND", "记录不存在或无权访问", HttpStatus.NOT_FOUND);
  }
  public static BusinessException forbidden() {
    return new BusinessException("HIRING_FORBIDDEN", "当前角色不能执行此操作", HttpStatus.FORBIDDEN);
  }
  public static BusinessException conflict(String message) {
    return new BusinessException("HIRING_CONFLICT", message, HttpStatus.CONFLICT);
  }
  public static void version(long expected, long actual) {
    if (expected != actual) throw conflict("记录已更新，请刷新后重试");
  }
}
