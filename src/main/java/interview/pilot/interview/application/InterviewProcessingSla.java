package interview.pilot.interview.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import interview.pilot.ai.provider.AiProviderProperties;

@Component
public class InterviewProcessingSla {
  private static final long MAX_NANOS = Duration.ofHours(24).toNanos();
  private final Duration processingTimeout;
  private final Duration sseTimeout;
  private final long processingTimeoutNanos;
  private final long sseTimeoutMillis;

  public InterviewProcessingSla(
      AiProviderProperties properties,
      @Value("${app.interview.processing-margin:15s}") Duration processingMargin,
      @Value("${app.interview.sse-margin:15s}") Duration sseMargin) {
    if (properties.structuredMaxAttempts() < 1) {
      throw new IllegalStateException("Structured AI attempts must be at least one");
    }
    requireNonNegative(processingMargin, "processing margin");
    requireNonNegative(sseMargin, "SSE margin");
    Duration maxProvider = properties.providers().values().stream()
        .filter(provider -> provider.enabled() && provider.isComplete())
        .map(AiProviderProperties.Provider::timeout)
        .max(Duration::compareTo)
        .orElse(Duration.ZERO);
    try {
      // Each registry timeout is independently applied to connect and read. A turn performs at
      // most two sequential structured operations, each with structuredMaxAttempts calls.
      long networkNanos = Math.multiplyExact(
          maxProvider.toNanos(), Math.multiplyExact(4L, properties.structuredMaxAttempts()));
      long processingNanos = Math.addExact(networkNanos, processingMargin.toNanos());
      long sseNanos = Math.addExact(processingNanos, sseMargin.toNanos());
      if (processingNanos <= 0 || sseNanos <= processingNanos || sseNanos > MAX_NANOS) {
        throw new IllegalStateException("Interview processing SLA is outside safe bounds");
      }
      this.processingTimeout = Duration.ofNanos(processingNanos);
      this.sseTimeout = Duration.ofNanos(sseNanos);
      this.processingTimeoutNanos = processingNanos;
      this.sseTimeoutMillis = sseTimeout.toMillis();
      if (sseTimeoutMillis <= 0) {
        throw new IllegalStateException("Interview SSE timeout is too small");
      }
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("Interview processing SLA is outside safe bounds");
    }
  }

  public Duration processingTimeout() { return processingTimeout; }
  public Duration sseTimeout() { return sseTimeout; }
  public long processingTimeoutNanos() { return processingTimeoutNanos; }
  public long sseTimeoutMillis() { return sseTimeoutMillis; }

  private void requireNonNegative(Duration duration, String name) {
    if (duration == null || duration.isNegative()) {
      throw new IllegalStateException(name + " must not be negative");
    }
  }
}
