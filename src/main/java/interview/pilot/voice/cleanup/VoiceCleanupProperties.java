package interview.pilot.voice.cleanup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Voice media cleanup tuning ({@code app.voice.cleanup.*}), separate from {@code VoiceProperties}
 * because the schedule knobs are operational knobs, not voice capability configuration. The
 * schedule cadence itself lives on the scheduler's {@code @Scheduled} placeholders
 * ({@code app.voice.cleanup-interval} / {@code app.voice.cleanup-initial-delay}).
 *
 * @param batchSize candidates claimed per sweep phase per run (the batch is bounded on purpose)
 * @param stuckTaskThreshold age of a PUBLISHED voice task with no row activity after which the
 *        matching TRANSCRIBING recording / SYNTHESIZING speech is treated as orphaned
 * @param orphanGrace minimum file age (by mtime) before an unreferenced media file may be
 *        deleted — protects an in-flight store that will soon commit a row referencing the key
 * @param claimTtl Redis processing-claim TTL covering one whole cleanup run
 */
@ConfigurationProperties("app.voice.cleanup")
public record VoiceCleanupProperties(
    @DefaultValue("50") int batchSize,
    @DefaultValue("PT30M") Duration stuckTaskThreshold,
    @DefaultValue("PT24H") Duration orphanGrace,
    @DefaultValue("PT30M") Duration claimTtl) {

  public VoiceCleanupProperties {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("Voice cleanup batch size must be positive");
    }
    positive(stuckTaskThreshold, "stuck task threshold");
    positive(orphanGrace, "orphan grace");
    positive(claimTtl, "claim ttl");
  }

  private static void positive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("Voice cleanup " + name + " must be positive");
    }
  }
}
