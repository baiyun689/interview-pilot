package interview.pilot.ai;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.model.AiResponse;
import interview.pilot.ai.provider.AiProviderProperties;
import interview.pilot.common.observability.AiMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
public class StructuredOutputInvoker {
  private final AiGateway gateway;
  private final ObjectMapper objectMapper;
  private final AiProviderProperties properties;
  private final MeterRegistry meterRegistry;
  private final AiMetrics metrics;

  public StructuredOutputInvoker(
      AiGateway gateway,
      ObjectMapper objectMapper,
      AiProviderProperties properties,
      MeterRegistry meterRegistry,
      AiMetrics metrics) {
    this.gateway = gateway;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    this.metrics = metrics;
  }

  public <T> T invoke(AiRequest request, Class<T> targetType) {
    if (request == null || targetType == null) {
      throw new IllegalArgumentException("AI request and target type are required");
    }
    int maxAttempts = properties.structuredMaxAttempts();
    if (maxAttempts < 1) {
      throw new IllegalStateException("app.ai.structured-max-attempts must be at least 1");
    }

    AiRequest currentRequest = request;
    String parseReason = "Invalid JSON";
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      if (attempt > 1) metrics.structuredRetry(request.providerId());
      meterRegistry.counter(
          "ai.structured.output.attempts",
          "provider", metricProvider(request.providerId())).increment();
      AiResponse response = gateway.generate(currentRequest);
      try {
        if (response.content() == null || response.content().isBlank()) {
          parseReason = "Empty JSON response";
        } else {
          T parsed = objectMapper.readValue(response.content(), targetType);
          if (parsed != null) {
            return parsed;
          }
          parseReason = "JSON shape did not match the requested type";
        }
      } catch (JacksonException exception) {
        parseReason = sanitizedParseReason(exception);
      }

      if (attempt < maxAttempts) {
        currentRequest = repairRequest(request, targetType, parseReason);
      }
    }

    meterRegistry.counter(
        "ai.structured.output.exhausted",
        "provider", metricProvider(request.providerId())).increment();
    log.warn("structured_output_exhausted provider={} reason={}",
        metricProvider(request.providerId()), parseReason);
    throw new AiStructuredOutputException(parseReason);
  }

  private AiRequest repairRequest(AiRequest original, Class<?> targetType, String parseReason) {
    String repairPrompt = original.userPrompt()
        + "\n\nThe previous response could not be parsed: " + parseReason + "."
        + " Return only valid JSON matching " + targetType.getSimpleName() + ".";
    return new AiRequest(
        original.providerId(),
        original.expectedModel(),
        original.systemPrompt(),
        repairPrompt,
        original.responseType());
  }

  private String sanitizedParseReason(JacksonException exception) {
    return exception instanceof StreamReadException
        ? "Malformed JSON"
        : "JSON shape did not match the requested type";
  }

  private String metricProvider(String providerId) {
    return providerId == null || providerId.isBlank() ? "default" : providerId;
  }
}
