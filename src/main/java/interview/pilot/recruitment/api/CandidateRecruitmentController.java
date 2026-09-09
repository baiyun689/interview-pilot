package interview.pilot.recruitment.api;

import static interview.pilot.recruitment.application.HiringModels.*;
import org.springframework.web.bind.annotation.*;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.RecruitmentService;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CandidateRecruitmentController {
  private final RecruitmentService service;
  private final CurrentUserProvider users;
  public CandidateRecruitmentController(RecruitmentService service, CurrentUserProvider users) {
    this.service = service; this.users = users;
  }
  @GetMapping("/public/jobs")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public Page<JobView> jobs(@RequestParam(defaultValue = "0") int page) { return service.publicJobs(page); }
  @GetMapping("/public/jobs/{jobId}")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public JobView job(@PathVariable Long jobId) { return service.publicJob(jobId); }
  @PostMapping("/candidate/jobs/{jobId}/applications")
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public ApplicationView apply(@PathVariable Long jobId, @Valid @RequestBody ApplicationInput input) {
    return service.apply(users.require(), jobId, input);
  }
  @GetMapping("/candidate/applications")
  public Page<ApplicationView> applications(@RequestParam(defaultValue = "0") int page) {
    return service.mine(users.require(), page);
  }
  @GetMapping("/candidate/applications/{id}")
  public ApplicationDetail detail(@PathVariable Long id) { return service.detail(users.require(), null, id); }
  @PostMapping("/candidate/applications/{id}/withdraw")
  public ApplicationView withdraw(@PathVariable Long id, @Valid @RequestBody VersionInput input) {
    return service.withdraw(users.require(), id, input.version());
  }
}
