package interview.pilot.recruitment.api;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.HiringPlatformService;

@RestController
@RequestMapping("/api/platform")
public class HiringPlatformController {
  private final HiringPlatformService platform;
  private final CurrentUserProvider users;
  public HiringPlatformController(HiringPlatformService platform, CurrentUserProvider users) {
    this.platform = platform; this.users = users;
  }
  public record Access(boolean allowed) {}
  public record ActiveInput(boolean active) {}
  @GetMapping("/access")
  public Access access() { return new Access(platform.allowed(users.require())); }
  @GetMapping("/organizations")
  public List<HiringPlatformService.OrganizationStatus> organizations(@RequestParam(defaultValue = "0") int page) {
    return platform.organizations(users.require(), page);
  }
  @PutMapping("/organizations/{id}/status")
  public void status(@PathVariable Long id, @RequestBody ActiveInput input) {
    platform.setActive(users.require(), id, input.active());
  }
}
