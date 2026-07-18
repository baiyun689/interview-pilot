package interview.pilot.ai.provider;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;

@RestController
@RequestMapping("/api/ai/providers")
public class AiProviderController {
  private final AiProviderService service;

  public AiProviderController(AiProviderService service) {
    this.service = service;
  }

  @GetMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<AiProviderDescriptor> list() {
    return service.list();
  }

  @PostMapping("/{id}/test")
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  public AiProviderService.ProviderTestResult test(@PathVariable String id) {
    return service.test(id);
  }

  @PutMapping("/default")
  @RateLimit(scope = RateLimitScope.IP, capacity = 30)
  public AiProviderDescriptor switchDefault(@Valid @RequestBody SwitchDefaultRequest request) {
    return service.switchDefault(request.providerId());
  }

  public record SwitchDefaultRequest(@NotBlank String providerId) {}
}
