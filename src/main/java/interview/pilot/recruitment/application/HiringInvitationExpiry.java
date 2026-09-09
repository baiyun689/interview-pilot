package interview.pilot.recruitment.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HiringInvitationExpiry {
  private final HiringInvitationService invitations;
  public HiringInvitationExpiry(HiringInvitationService invitations) { this.invitations=invitations; }
  @Scheduled(fixedDelayString="${app.hiring.invitation-expiry-interval:30s}",initialDelayString="${app.hiring.invitation-expiry-interval:30s}")
  public void run() { for (Long id : invitations.expiredCandidates()) invitations.expire(id); }
}
