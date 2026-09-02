package interview.pilot.voice.cleanup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry for the voice media cleanup sweeper (plan §14, Task 11). The cadence is
 * configurable through {@code app.voice.cleanup-interval} / {@code app.voice.cleanup-initial-delay}
 * (ISO-8601 durations, default 10m / 2m); tests pin the values far in the future so the batch
 * runs only when a test invokes {@link VoiceMediaCleanupService#runCleanup()} directly. Like
 * the other scheduled tasks ({@code KnowledgeRevisionCleanup}, {@code PendingTaskDispatcher}),
 * scheduling itself is already enabled globally via {@code @EnableScheduling} on the
 * application class — no voice-specific enablement is needed.
 */
@Component
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceMediaCleanupScheduler {

  private final VoiceMediaCleanupService cleanup;

  public VoiceMediaCleanupScheduler(VoiceMediaCleanupService cleanup) {
    this.cleanup = cleanup;
  }

  @Scheduled(
      fixedDelayString = "${app.voice.cleanup-interval:PT10M}",
      initialDelayString = "${app.voice.cleanup-initial-delay:PT2M}")
  public void cleanupExpiredVoiceMedia() {
    cleanup.runCleanup();
  }
}
