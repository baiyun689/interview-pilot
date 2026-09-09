package interview.pilot.recruitment.api;

import static interview.pilot.recruitment.application.AssessmentModels.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.HiringAssessmentService;
import interview.pilot.recruitment.application.HiringModels.VersionInput;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/organizations/{orgId}")
public class HiringAssessmentController {
  private final HiringAssessmentService service;
  private final CurrentUserProvider users;
  public HiringAssessmentController(HiringAssessmentService service, CurrentUserProvider users) { this.service = service; this.users = users; }
  @PostMapping("/applications/{id}/analysis")
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public WorkView analyze(@PathVariable Long orgId, @PathVariable Long id, @Valid @RequestBody AnalysisRequest input) {
    return service.analyze(users.require(), orgId, id, input.providerId());
  }
  @GetMapping("/applications/{id}/analysis")
  public WorkView analysis(@PathVariable Long orgId, @PathVariable Long id) { return service.analysis(users.require(), orgId, id); }
  @PostMapping("/analysis-tasks/{id}/retry")
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  public WorkView retry(@PathVariable Long orgId, @PathVariable Long id) { return service.retryAnalysis(users.require(), orgId, id); }
  @GetMapping("/jobs/{jobId}/schemes")
  public List<SchemeView> schemes(@PathVariable Long orgId, @PathVariable Long jobId) { return service.schemes(users.require(), orgId, jobId); }
  @PostMapping("/jobs/{jobId}/schemes")
  public SchemeView create(@PathVariable Long orgId, @PathVariable Long jobId, @Valid @RequestBody SchemeInput input) {
    return service.saveScheme(users.require(), orgId, jobId, null, input);
  }
  @PutMapping("/jobs/{jobId}/schemes/{id}")
  public SchemeView save(@PathVariable Long orgId, @PathVariable Long jobId, @PathVariable Long id, @Valid @RequestBody SchemeInput input) {
    return service.saveScheme(users.require(), orgId, jobId, id, input);
  }
  @PostMapping("/schemes/{id}/publish")
  public SchemeRevisionView publish(@PathVariable Long orgId, @PathVariable Long id, @Valid @RequestBody VersionInput input) {
    return service.publishScheme(users.require(), orgId, id, input.version());
  }
  @GetMapping("/schemes/{id}/revisions")
  public List<SchemeRevisionView> revisions(@PathVariable Long orgId, @PathVariable Long id) { return service.revisions(users.require(), orgId, id); }
  @PostMapping("/schemes/{id}/revisions/{revision}/retire")
  public void retire(@PathVariable Long orgId, @PathVariable Long id, @PathVariable int revision) { service.retireRevision(users.require(), orgId, id, revision); }
}
