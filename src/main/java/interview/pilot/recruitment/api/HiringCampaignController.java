package interview.pilot.recruitment.api;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.HiringCampaignService;
import static interview.pilot.recruitment.application.CampaignModels.*;
import interview.pilot.common.ratelimit.*;

@RestController
@RequestMapping("/api/organizations/{orgId}")
public class HiringCampaignController {
  private final HiringCampaignService service;
  private final CurrentUserProvider users;
  public HiringCampaignController(HiringCampaignService service, CurrentUserProvider users) { this.service = service; this.users = users; }
  @PostMapping("/jobs/{jobId}/batches")
  @RateLimit(scope = RateLimitScope.USER, capacity = 5, expensive = true)
  public BatchDetail create(@PathVariable Long orgId, @PathVariable Long jobId, @Valid @RequestBody BatchInput input) { return service.create(users.require(), orgId, jobId, input); }
  @GetMapping("/jobs/{jobId}/batches")
  public List<BatchView> list(@PathVariable Long orgId, @PathVariable Long jobId) { return service.list(users.require(), orgId, jobId); }
  @GetMapping("/batches/{id}")
  public BatchDetail detail(@PathVariable Long orgId, @PathVariable Long id) { return service.detail(users.require(), orgId, id); }
  @PutMapping("/batches/{id}/members/{memberId}/approval")
  public MemberView approve(@PathVariable Long orgId, @PathVariable Long id, @PathVariable Long memberId, @Valid @RequestBody ApprovalInput input) { return service.approve(users.require(), orgId, id, memberId, input); }
  @PostMapping("/batches/{id}/members/{memberId}/retry")
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public void retry(@PathVariable Long orgId, @PathVariable Long id, @PathVariable Long memberId) { service.retry(users.require(), orgId, id, memberId); }
  @PostMapping("/batches/{id}/publish")
  public PublishResult publish(@PathVariable Long orgId, @PathVariable Long id) { return service.publish(users.require(), orgId, id); }
}
