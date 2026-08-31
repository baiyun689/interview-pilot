package interview.pilot.interview.voice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import interview.pilot.auth.application.CurrentUser;

/** One-use, short-lived ticket bridging an authenticated HTTP request to a browser WS handshake. */
@Service
public final class VoiceTicketService {
  private static final long TTL_SECONDS = 60;
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  public String issue(CurrentUser user, UUID sessionId) {
    String value = UUID.randomUUID().toString();
    tickets.put(value, new Ticket(user, sessionId, Instant.now().plusSeconds(TTL_SECONDS)));
    return value;
  }

  public synchronized CurrentUser consume(String value, UUID sessionId) {
    if (value == null || value.isBlank()) return null;
    Ticket ticket = tickets.get(value);
    if (ticket == null || !ticket.sessionId().equals(sessionId)
        || ticket.expiresAt().isBefore(Instant.now())) return null;
    tickets.remove(value, ticket);
    return ticket.user();
  }

  @Scheduled(fixedDelay = 60_000)
  void removeExpired() {
    Instant now = Instant.now();
    tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
  }

  private record Ticket(CurrentUser user, UUID sessionId, Instant expiresAt) { }
}
