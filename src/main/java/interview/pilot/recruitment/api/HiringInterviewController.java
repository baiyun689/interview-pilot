package interview.pilot.recruitment.api;

import org.springframework.web.bind.annotation.*;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.HiringInterviewService;

@RestController
@RequestMapping("/api/candidate/invitations")
public class HiringInterviewController {
  private final HiringInterviewService service;
  private final CurrentUserProvider users;
  public HiringInterviewController(HiringInterviewService service, CurrentUserProvider users) {this.service=service;this.users=users;}
  @PostMapping("/{id}/start")
  public HiringInterviewService.Started start(@PathVariable String id) { return service.start(users.require(), id); }
}
