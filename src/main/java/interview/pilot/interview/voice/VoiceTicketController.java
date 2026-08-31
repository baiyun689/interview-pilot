package interview.pilot.interview.voice;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;

@RestController
@RequestMapping("/api/interviews")
public final class VoiceTicketController {
  private final CurrentUserProvider currentUser;
  private final VoiceTicketService tickets;

  public VoiceTicketController(CurrentUserProvider currentUser, VoiceTicketService tickets) {
    this.currentUser = currentUser;
    this.tickets = tickets;
  }

  @PostMapping("/{sessionId}/voice-ticket")
  @RateLimit(scope = RateLimitScope.USER, capacity = 20)
  public VoiceTicketResponse issue(@PathVariable UUID sessionId) {
    return new VoiceTicketResponse(tickets.issue(currentUser.require(), sessionId));
  }

  public record VoiceTicketResponse(String ticket) { }
}
