package interview.pilot.async.infrastructure;

import java.time.Duration;

/** Common bounds for database execution leases, measured by MySQL's clock. */
public final class ExecutionLeaseDuration {
  private ExecutionLeaseDuration() {}

  public static long seconds(Duration duration) {
    if (duration == null || duration.getSeconds() < 1 || duration.getNano() != 0
        || duration.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("Execution lease must be whole seconds between 1s and 1h");
    }
    return duration.getSeconds();
  }
}
