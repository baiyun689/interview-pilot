package interview.pilot.recruitment.api;

import static interview.pilot.recruitment.application.HiringModels.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.OrganizationService;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {
  private final OrganizationService service;
  private final CurrentUserProvider users;
  public OrganizationController(OrganizationService service, CurrentUserProvider users) {
    this.service = service; this.users = users;
  }
  @GetMapping
  public List<OrganizationView> mine() { return service.mine(users.require()); }
  @PostMapping
  @RateLimit(scope = RateLimitScope.USER, capacity = 5)
  public OrganizationView create(@Valid @RequestBody OrganizationInput input) {
    return service.create(users.require(), input);
  }
  @GetMapping("/{orgId}/members")
  public List<MemberView> members(@PathVariable Long orgId) { return service.members(users.require(), orgId); }
  @PutMapping("/{orgId}")
  public OrganizationView rename(@PathVariable Long orgId, @Valid @RequestBody OrganizationInput input) {
    return service.rename(users.require(), orgId, input);
  }
  @PostMapping("/{orgId}/member-invitations")
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public InvitationView invite(@PathVariable Long orgId, @Valid @RequestBody MemberInput input) {
    return service.invite(users.require(), orgId, input);
  }
  public record AcceptInput(@NotBlank @Size(max = 128) String token) {}
  @PostMapping("/member-invitations/accept")
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public OrganizationView accept(@Valid @RequestBody AcceptInput input) {
    return service.accept(users.require(), input.token());
  }
  @PutMapping("/{orgId}/members/{userId}")
  public void update(@PathVariable Long orgId, @PathVariable Long userId, @Valid @RequestBody MemberUpdate input) {
    service.updateMember(users.require(), orgId, userId, input);
  }
  @GetMapping("/{orgId}/audit")
  public List<AuditView> audit(@PathVariable Long orgId, @RequestParam(defaultValue = "0") int page) {
    return service.auditLog(users.require(), orgId, page);
  }
}
