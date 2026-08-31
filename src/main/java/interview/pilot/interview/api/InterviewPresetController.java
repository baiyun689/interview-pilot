package interview.pilot.interview.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;
import interview.pilot.interview.preset.InterviewPreset;
import interview.pilot.interview.preset.InterviewPresetCatalog;

@RestController
@RequestMapping("/api/interview-presets")
public class InterviewPresetController {
  private final InterviewPresetCatalog catalog;

  public InterviewPresetController(InterviewPresetCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<InterviewPresetResponse> list() {
    return catalog.list().stream().map(InterviewPresetResponse::from).toList();
  }

  public record InterviewPresetResponse(
      String id, String displayName, String description, String version) {
    static InterviewPresetResponse from(InterviewPreset preset) {
      return new InterviewPresetResponse(
          preset.id(), preset.displayName(), preset.description(), preset.version());
    }
  }
}
