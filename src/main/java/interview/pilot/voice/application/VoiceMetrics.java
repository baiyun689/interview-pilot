package interview.pilot.voice.application;

import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

import interview.pilot.voice.cleanup.VoiceCleanupMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer seam for the voice interview (plan §16). One small class with bounded tags,
 * mirroring the {@link VoiceCleanupMetrics} sanitization precedent.
 *
 * <p>Emission semantics:
 * <ul>
 *   <li>{@code interview_pilot.voice.upload.total{result,mime}} counts every accepted /
 *       rejected / replayed upload attempt; {@code upload.bytes} and
 *       {@code recording.duration} carry the probed metadata of accepted uploads (bytes are
 *       also recorded for the replayed upload). {@code result} is {@code accepted},
 *       {@code rejected} or {@code replayed}; {@code mime} is the probed media type
 *       ({@code unknown} when the probe itself failed).</li>
 *   <li>{@code asr.duration{provider,model,result}} and {@code tts.duration{provider,model,result}}
 *       time the PROVIDER CALL of the terminal attempt and record the terminal outcome only
 *       ({@code success} / {@code failure}) — transient retryable attempts are not recorded,
 *       so the failure share of the dashboard is the deterministic-failure share, not the
 *       retry noise. {@code asr.transcript.characters} and {@code tts.audio.bytes} are only
 *       recorded on success; {@code model} is {@code unknown} on failure (no transcript /
 *       synthesized audio was returned).</li>
 *   <li>{@code retry.total{taskType,reason}} counts manual retries ({@code reason=manual})
 *       from the two module retry endpoints and retry-budget exhaustion
 *       ({@code reason=exhausted}) when a voice task dead-letters; {@code taskType} is
 *       {@code voice_transcription} or {@code question_speech_synthesis}.</li>
 *   <li>{@code fallback.total{reason}} is an APPROXIMATION (plan §16): the discard endpoint
 *       cannot distinguish a re-record from an explicit switch to text — both call discard —
 *       so the counter counts user-initiated discard events of unbound recordings as
 *       {@code reason=user_discard}, a proxy for "the user did not keep this voice answer".
 *       Dashboard it as a trend signal, not as a precise fallback funnel.</li>
 * </ul>
 */
@Component
public class VoiceMetrics {

  private final MeterRegistry meters;

  public VoiceMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  public void upload(String result, String mime, Long bytes, Long recordingDurationMillis) {
    meters.counter("interview_pilot.voice.upload.total",
        "result", strict(result), "mime", dimension(mime)).increment();
    if (bytes != null) {
      meters.counter("interview_pilot.voice.upload.bytes").increment(bytes);
    }
    if (recordingDurationMillis != null) {
      meters.summary("interview_pilot.voice.recording.duration")
          .record(recordingDurationMillis);
    }
  }

  public void asr(
      String provider, String model, String result, Duration latency,
      Integer transcriptCharacters) {
    meters.timer("interview_pilot.voice.asr.duration",
        "provider", dimension(provider), "model", dimension(model),
        "result", strict(result)).record(latency);
    if (transcriptCharacters != null) {
      meters.counter("interview_pilot.voice.asr.transcript.characters")
          .increment(transcriptCharacters);
    }
  }

  public void tts(
      String provider, String model, String result, Duration latency, Long audioBytes) {
    meters.timer("interview_pilot.voice.tts.duration",
        "provider", dimension(provider), "model", dimension(model),
        "result", strict(result)).record(latency);
    if (audioBytes != null) {
      meters.counter("interview_pilot.voice.tts.audio.bytes").increment(audioBytes);
    }
  }

  public void retry(String taskType, String reason) {
    meters.counter("interview_pilot.voice.retry.total",
        "taskType", strict(taskType), "reason", strict(reason)).increment();
  }

  public void fallback(String reason) {
    meters.counter("interview_pilot.voice.fallback.total",
        "reason", strict(reason)).increment();
  }

  /** Enum-like values ({@code result}/{@code reason}/{@code taskType}) get a strict bound. */
  private static String strict(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    return value.length() <= 40 && value.matches("[a-z0-9_]+") ? value : "other";
  }

  /**
   * Free-form values (provider/model/mime, e.g. {@code audio/webm}) get normalized
   * dimension-style: lowercased, non-alphanumeric characters (except {@code .}, {@code _},
   * {@code -} and the mime {@code /}) replaced, capped at 64.
   */
  private static String dimension(String value) {
    if (value == null || value.isBlank()) {
      return "unknown";
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}._/-]", "_");
    return normalized.length() <= 64 ? normalized : "other";
  }
}
