package interview.pilot.recruitment.api;

import static interview.pilot.recruitment.application.HiringModels.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.RecruitmentService;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/organizations/{orgId}")
public class RecruitmentController {
  private final RecruitmentService service;
  private final CurrentUserProvider users;
  public RecruitmentController(RecruitmentService service, CurrentUserProvider users) {
    this.service = service; this.users = users;
  }
  @GetMapping("/jobs")
  public Page<JobView> jobs(@PathVariable Long orgId, @RequestParam(defaultValue = "0") int page) {
    return service.companyJobs(users.require(), orgId, page);
  }
  @PostMapping("/jobs")
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public JobView create(@PathVariable Long orgId, @Valid @RequestBody JobInput input) {
    return service.createJob(users.require(), orgId, input);
  }
  @PutMapping("/jobs/{jobId}")
  public JobView update(@PathVariable Long orgId, @PathVariable Long jobId, @Valid @RequestBody JobInput input) {
    return service.updateJob(users.require(), orgId, jobId, input);
  }
  @PostMapping("/jobs/{jobId}/publish")
  public JobView publish(@PathVariable Long orgId, @PathVariable Long jobId, @Valid @RequestBody VersionInput input) {
    return service.publishJob(users.require(), orgId, jobId, input.version());
  }
  @PostMapping("/jobs/{jobId}/close")
  public JobView close(@PathVariable Long orgId, @PathVariable Long jobId, @Valid @RequestBody VersionInput input) {
    return service.closeJob(users.require(), orgId, jobId, input.version());
  }
  @GetMapping("/jobs/{jobId}/assignments")
  public List<Long> assignments(@PathVariable Long orgId, @PathVariable Long jobId) {
    return service.assignments(users.require(), orgId, jobId);
  }
  @PutMapping("/jobs/{jobId}/assignments/{userId}")
  public void assign(@PathVariable Long orgId, @PathVariable Long jobId, @PathVariable Long userId) {
    service.assignJob(users.require(), orgId, jobId, userId, true);
  }
  @DeleteMapping("/jobs/{jobId}/assignments/{userId}")
  public void unassign(@PathVariable Long orgId, @PathVariable Long jobId, @PathVariable Long userId) {
    service.assignJob(users.require(), orgId, jobId, userId, false);
  }
  @GetMapping("/jobs/{jobId}/applications")
  public Page<ApplicationView> applications(@PathVariable Long orgId, @PathVariable Long jobId,
      @RequestParam(defaultValue = "0") int page) { return service.applications(users.require(), orgId, jobId, page); }
  @GetMapping("/applications/{id}")
  public ApplicationDetail detail(@PathVariable Long orgId, @PathVariable Long id) {
    return service.detail(users.require(), orgId, id);
  }
}
