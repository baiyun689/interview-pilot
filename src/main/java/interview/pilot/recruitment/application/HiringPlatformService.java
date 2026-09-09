package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import static interview.pilot.recruitment.application.HiringAccess.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.recruitment.infrastructure.HiringStore;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class HiringPlatformService {
  private final HiringStore store;
  private final OrganizationService organizations;
  public HiringPlatformService(HiringStore store, OrganizationService organizations) {
    this.store = store; this.organizations = organizations;
  }
  public record OrganizationStatus(Long id, String name, boolean active) {}

  @Transactional(readOnly = true)
  public boolean allowed(CurrentUser user) {
    return store.one(PlatformOperator.class,
        "from HiringPlatformOperator where userAccountId=?1 and active=true", user.databaseId()).isPresent();
  }

  @Transactional(readOnly = true)
  public List<OrganizationStatus> organizations(CurrentUser user, int page) {
    requireOperator(user);
    if (page < 0 || page > 10000) throw conflict("页码超出范围");
    return store.list(Organization.class, "from HiringOrganization order by id desc", page * 25, 25)
        .stream().map(o -> new OrganizationStatus(o.id, o.name, o.active)).toList();
  }

  public void setActive(CurrentUser user, Long orgId, boolean active) {
    requireOperator(user);
    var organization = store.find(Organization.class, orgId, true).orElseThrow(HiringAccess::notFound);
    organization.active = active;
    organizations.audit(user, orgId, active ? "ORGANIZATION_ENABLED" : "ORGANIZATION_DISABLED", orgId);
  }

  private void requireOperator(CurrentUser user) { if (!allowed(user)) throw forbidden(); }
}
