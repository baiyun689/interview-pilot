package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.HiringAccess.*;
import static interview.pilot.recruitment.application.HiringModels.*;
import static interview.pilot.recruitment.infrastructure.HiringEntities.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.recruitment.infrastructure.HiringStore;

@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class OrganizationService {
  private final HiringStore store;
  private final HiringAccess access;
  public OrganizationService(HiringStore store, HiringAccess access) {
    this.store = store; this.access = access;
  }

  public OrganizationView create(CurrentUser user, OrganizationInput input) {
    var org = new Organization();
    org.name = input.name().trim(); org.createdAt = Instant.now();
    store.add(org);
    var member = new Membership();
    member.organizationId = org.id; member.userAccountId = user.databaseId();
    member.role = "ADMIN"; member.active = true; store.add(member);
    audit(user, org.id, "ORGANIZATION_CREATED", org.id);
    return new OrganizationView(org.id, org.name, Role.ADMIN);
  }

  public OrganizationView rename(CurrentUser user, Long orgId, OrganizationInput input) {
    access.admin(user, orgId, true);
    var org = store.find(Organization.class, orgId, false).orElseThrow();
    org.name = input.name().trim();
    audit(user, orgId, "ORGANIZATION_RENAMED", orgId);
    return new OrganizationView(orgId, org.name, Role.ADMIN);
  }

  @Transactional(readOnly = true)
  public List<OrganizationView> mine(CurrentUser user) {
    return store.list(Membership.class,
        "from HiringMembership m where userAccountId=?1 and active=true and exists (select o.id from HiringOrganization o where o.id=m.organizationId and o.active=true) order by id", 0, 100,
        user.databaseId()).stream().map(member -> new OrganizationView(member.organizationId,
            store.find(Organization.class, member.organizationId, false).orElseThrow().name,
            Role.valueOf(member.role))).toList();
  }

  @Transactional(readOnly = true)
  public List<MemberView> members(CurrentUser user, Long orgId) {
    access.admin(user, orgId, false);
    return store.list(Membership.class, "from HiringMembership where organizationId=?1 order by id",
        0, 200, orgId).stream().map(member -> {
          var account = store.find(UserAccountEntity.class, member.userAccountId, false).orElseThrow();
          return new MemberView(member.userAccountId, account.getDisplayName(), account.getEmail(),
              Role.valueOf(member.role), member.active);
        }).toList();
  }

  public InvitationView invite(CurrentUser user, Long orgId, MemberInput input) {
    access.admin(user, orgId, true);
    byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var invitation = new MemberInvitation();
    invitation.organizationId = orgId;
    invitation.email = input.email().trim().toLowerCase(Locale.ROOT);
    invitation.role = input.role().name(); invitation.tokenHash = hash(token);
    invitation.expiresAt = Instant.now().plus(7, ChronoUnit.DAYS); store.add(invitation);
    audit(user, orgId, "MEMBER_INVITED", invitation.id);
    return new InvitationView(invitation.id, token, invitation.expiresAt);
  }

  public OrganizationView accept(CurrentUser user, String token) {
    var reference = store.one(MemberInvitation.class,
        "from HiringMemberInvitation where tokenHash=?1", hash(token)).orElseThrow(HiringAccess::notFound);
    var org = store.find(Organization.class, reference.organizationId, true).orElseThrow();
    if (!org.active) throw notFound();
    var invitation = store.find(MemberInvitation.class, reference.id, true).orElseThrow();
    if (!invitation.email.equalsIgnoreCase(user.email())) throw notFound();
    var current = store.one(Membership.class,
        "from HiringMembership where organizationId=?1 and userAccountId=?2", org.id, user.databaseId());
    if (invitation.acceptedAt != null) {
      var existing = current.filter(it -> it.active).orElseThrow(HiringAccess::notFound);
      return new OrganizationView(org.id, org.name, Role.valueOf(existing.role));
    }
    if (!invitation.expiresAt.isAfter(Instant.now())) throw conflict("成员邀请已过期");
    // An outstanding old invite must not silently change an existing member's role or reactivate them.
    if (current.isPresent()) throw conflict("已存在成员记录，请管理员直接调整成员权限");
    var member = new Membership(); member.organizationId = org.id;
    member.userAccountId = user.databaseId(); member.role = invitation.role; member.active = true;
    store.add(member); invitation.acceptedAt = Instant.now();
    audit(user, org.id, "MEMBER_JOINED", member.id);
    return new OrganizationView(org.id, org.name, Role.valueOf(member.role));
  }

  public void updateMember(CurrentUser user, Long orgId, Long accountId, MemberUpdate input) {
    access.admin(user, orgId, true);
    var member = store.one(Membership.class,
        "from HiringMembership where organizationId=?1 and userAccountId=?2", orgId, accountId)
        .orElseThrow(HiringAccess::notFound);
    if (member.active && member.role.equals("ADMIN") && (!input.active() || input.role() != Role.ADMIN)) {
      long admins = store.one(Long.class,
          "select count(m) from HiringMembership m where organizationId=?1 and active=true and role='ADMIN'",
          orgId).orElseThrow();
      if (admins <= 1) throw conflict("不能移除最后一名有效企业管理员");
    }
    member.role = input.role().name(); member.active = input.active();
    audit(user, orgId, "MEMBER_UPDATED", member.id);
  }

  @Transactional(readOnly = true)
  public List<AuditView> auditLog(CurrentUser user, Long orgId, int page) {
    access.admin(user, orgId, false);
    return store.list(AuditEvent.class, "from HiringAuditEvent where organizationId=?1 order by id desc",
        Math.multiplyExact(Math.max(0, Math.min(page, 10000)), 25), 25, orgId).stream()
        .map(e -> new AuditView(e.id, e.actorId, e.action, e.resourceId, e.createdAt)).toList();
  }

  public void audit(CurrentUser user, Long orgId, String action, Long resourceId) {
    var event = new AuditEvent(); event.organizationId = orgId; event.actorId = user.databaseId();
    event.action = action; event.resourceId = resourceId; event.createdAt = Instant.now(); store.add(event);
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
  }
}
