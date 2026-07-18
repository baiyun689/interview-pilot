package interview.pilot.interview.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import interview.pilot.interview.skill.InterviewSkillCatalog;

@RestController
@RequestMapping("/api/interview-skills")
public class InterviewSkillController {
  private final InterviewSkillCatalog catalog;

  public InterviewSkillController(InterviewSkillCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<InterviewSkillResponse> list() {
    return catalog.list().stream().map(InterviewSkillResponse::from).toList();
  }
}
