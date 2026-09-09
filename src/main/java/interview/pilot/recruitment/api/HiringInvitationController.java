package interview.pilot.recruitment.api;

import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.*;
import static interview.pilot.recruitment.application.CampaignModels.*;

@RestController
@RequestMapping("/api")
public class HiringInvitationController {
  private final HiringInvitationService service;
  private final CurrentUserProvider users;
  public HiringInvitationController(HiringInvitationService service, CurrentUserProvider users) { this.service=service; this.users=users; }
  @GetMapping("/candidate/invitations")
  public HiringModels.Page<InvitationView> mine(@RequestParam(defaultValue="0") int page) { return service.mine(users.require(),page); }
  @GetMapping(value="/candidate/invitations/{id}/calendar", produces="text/calendar;charset=UTF-8")
  public org.springframework.http.ResponseEntity<byte[]> calendar(@PathVariable String id) {
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=interview.ics")
        .header("Cache-Control", "private, no-store")
        .body(service.calendar(users.require(), id));
  }
  @PutMapping("/candidate/invitations/{id}/schedule")
  public InvitationView schedule(@PathVariable String id,@Valid @RequestBody ScheduleInput input) { return service.schedule(users.require(),id,input); }
  @PostMapping("/candidate/invitations/{id}/decline")
  public InvitationView decline(@PathVariable String id,@Valid @RequestBody HiringModels.VersionInput input) { return service.decline(users.require(),id,input.version()); }
  @PostMapping("/organizations/{orgId}/batch-members/{id}/cancel-invitation")
  public void cancel(@PathVariable Long orgId,@PathVariable Long id) { service.cancel(users.require(),orgId,id); }
}
