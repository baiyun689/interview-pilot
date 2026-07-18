package interview.pilot.async.idempotency;

import java.time.Duration;
import java.util.Optional;

public interface ProcessingClaim {
  Optional<String> acquire(String businessKey, Duration ttl);

  boolean complete(String businessKey, String token, Duration ttl);

  boolean release(String businessKey, String token);

  ClearResult clearTerminal(String businessKey);

  enum ClearResult { ABSENT, CLEARED, ACTIVE }
}
