package interview.pilot.recruitment.api;

import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.application.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class HiringReviewController {
  private final HiringReviewService service;
  private final CurrentUserProvider users;
  public HiringReviewController(HiringReviewService service,CurrentUserProvider users) {this.service=service;this.users=users;}
  @GetMapping("/api/organizations/{org}/reviewers")
  public Object reviewers(@PathVariable Long org) {return service.reviewers(users.require(),org);}
  @GetMapping("/api/organizations/{org}/reviews")
  public Object list(@PathVariable Long org,@RequestParam(defaultValue="0") int page) {return service.list(users.require(),org,page);}
  @GetMapping("/api/organizations/{org}/reviews/{id}")
  public Object detail(@PathVariable Long org,@PathVariable String id) {return service.detail(users.require(),org,id);}
  @PutMapping("/api/organizations/{org}/reviews/{id}")
  public Object save(@PathVariable Long org,@PathVariable String id,@Valid @RequestBody HiringReviewService.Save input) {return service.save(users.require(),org,id,input);}
  @PostMapping("/api/organizations/{org}/reviews/{id}/publish")
  public Object publish(@PathVariable Long org,@PathVariable String id,@Valid @RequestBody HiringReviewService.Publish input) {return service.publish(users.require(),org,id,input);}
  @PutMapping("/api/organizations/{org}/reviews/{id}/assignments/{reviewer}")
  public void assign(@PathVariable Long org,@PathVariable String id,@PathVariable Long reviewer) {service.assign(users.require(),org,id,reviewer,true);}
  @DeleteMapping("/api/organizations/{org}/reviews/{id}/assignments/{reviewer}")
  public void unassign(@PathVariable Long org,@PathVariable String id,@PathVariable Long reviewer) {service.assign(users.require(),org,id,reviewer,false);}
  @GetMapping("/api/candidate/invitations/{id}/feedback")
  public Object feedback(@PathVariable String id) {return service.feedback(users.require(),id);}
}
