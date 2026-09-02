package interview.pilot.voice.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Local health check for the voice media root (plan §15): the actuator health endpoint must
 * never call a paid provider — it only verifies that the configured files root is writable
 * with a create-write-delete round trip (a couple of cheap file ops, no provider traffic).
 * The probe file is self-cleaning and short-lived; the cleanup sweeper's orphan grace (24h
 * by file age) can never confuse it with reclaimable media.
 *
 * <p>When {@code VOICE_ENABLED=false} the bean does not exist at all ({@code
 * @ConditionalOnProperty}), so a disabled voice can never degrade the health endpoint.
 */
@Component
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public class VoiceMediaHealthIndicator implements HealthIndicator {

  private final Path filesRoot;

  public VoiceMediaHealthIndicator(VoiceProperties voice) {
    this.filesRoot = voice.filesRoot();
  }

  @Override
  public Health health() {
    try {
      Files.createDirectories(filesRoot);
      Path probe = Files.createTempFile(filesRoot, ".voice-health-", ".probe");
      try {
        Files.writeString(probe, "ok", StandardCharsets.UTF_8);
      } finally {
        Files.deleteIfExists(probe);
      }
      return Health.up().build();
    } catch (IOException exception) {
      return Health.down()
          .withDetail("voiceFilesRoot", filesRoot.toString())
          .withException(exception)
          .build();
    }
  }
}
