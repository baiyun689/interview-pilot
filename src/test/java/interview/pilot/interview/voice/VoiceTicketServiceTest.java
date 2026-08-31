package interview.pilot.interview.voice;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import interview.pilot.auth.application.CurrentUser;

class VoiceTicketServiceTest {
  @Test
  void ticketIsOneUseAndBoundToTheInterviewSession() {
    var service = new VoiceTicketService();
    var user = new CurrentUser(7L, UUID.randomUUID(), "candidate@example.com", "Candidate");
    var session = UUID.randomUUID();
    String ticket = service.issue(user, session);

    assertThat(service.consume(ticket, UUID.randomUUID())).isNull();
    assertThat(service.consume(ticket, session)).isEqualTo(user);
    assertThat(service.consume(ticket, session)).isNull();
  }
}
