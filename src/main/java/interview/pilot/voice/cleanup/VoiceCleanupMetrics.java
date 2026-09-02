package interview.pilot.voice.cleanup;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer seam for the cleanup sweeper (plan §16: {@code interview_pilot.voice.cleanup.total}
 * {@code {result, kind}}). The counter is wired now so Task 12 only has to document and
 * dashboard it; {@code result} is {@code deleted} or {@code deferred} (file locked / storage
 * failure / security rejection — the row stays for a later run), {@code kind} identifies the
 * sweep phase. Task 12 should alert on a sustained {@code deferred} rate: a permanently
 * poisoned row (tampered key, eternally locked file) re-heads every tick and defers again,
 * which is exactly the signal that a manual fix is needed (see the head-of-line notes on
 * {@link VoiceMediaCleanupService}).
 */
@Component
public class VoiceCleanupMetrics {

  private static final String COUNTER = "interview_pilot.voice.cleanup.total";

  private final MeterRegistry meters;

  public VoiceCleanupMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  public void deleted(String kind) {
    count("deleted", kind);
  }

  public void deferred(String kind) {
    count("deferred", kind);
  }

  private void count(String result, String kind) {
    meters.counter(COUNTER, "result", bounded(result), "kind", bounded(kind)).increment();
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.length() <= 40 && value.matches("[a-z0-9_]+") ? value : "other";
  }
}
