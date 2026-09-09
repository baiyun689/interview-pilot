package interview.pilot.recruitment.api;

import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.HiringNotificationService;
import org.springframework.web.bind.annotation.*;

@RestController
public class HiringNotificationController {
  private final HiringNotificationService service;
  private final CurrentUserProvider users;
  public HiringNotificationController(HiringNotificationService service,CurrentUserProvider users) {this.service=service;this.users=users;}
  public record Retry(long version) {}
  @GetMapping("/api/notifications") public Object mine(@RequestParam(defaultValue="0") int page) {return service.mine(users.require(),page);}
  @PutMapping("/api/notifications/{id}/read") public void read(@PathVariable Long id) {service.read(users.require(),id);}
  @GetMapping("/api/organizations/{org}/notifications") public Object company(@PathVariable Long org,@RequestParam(defaultValue="0") int page) {return service.company(users.require(),org,page);}
  @PostMapping("/api/organizations/{org}/notifications/{id}/retry") public void retry(@PathVariable Long org,@PathVariable Long id,@RequestBody Retry input) {service.retry(users.require(),org,id,input.version());}
}
