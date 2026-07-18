package interview.pilot.common.observability;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.common.ratelimit.RateLimitUnavailableException;
import interview.pilot.common.ratelimit.RateLimitedException;

class TraceIdFilterTest {
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void acceptsOnlyCanonicalUuidAndUsesItForHeaderAttributeAndApiError() throws Exception {
    String traceId = "123e4567-e89b-12d3-a456-426614174000";
    var mvc = mvc();

    mvc.perform(get("/failure").header("X-Trace-Id", traceId))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string("X-Trace-Id", traceId))
        .andExpect(jsonPath("$.traceId").value(traceId));
  }

  @Test
  void invalidTraceIsNeverReflectedAndMdcIsRestoredAfterFailure() throws Exception {
    MDC.put("traceId", "outer");
    var mvc = mvc();

    mvc.perform(get("/failure").header("X-Trace-Id", "NOT-A-CANONICAL-UUID"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().string("X-Trace-Id", matchesPattern(UUID_PATTERN)))
        .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)));

    org.assertj.core.api.Assertions.assertThat(MDC.get("traceId")).isEqualTo("outer");
  }

  @Test
  void controlledRateErrorsUseTheSameTraceInHeaderAndBody() throws Exception {
    String traceId = "123e4567-e89b-12d3-a456-426614174000";
    mvc().perform(get("/limited").header("X-Trace-Id", traceId))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("X-Trace-Id", traceId))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.traceId").value(traceId));
    mvc().perform(get("/unavailable").header("X-Trace-Id", traceId))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("X-Trace-Id", traceId))
        .andExpect(jsonPath("$.code").value("RATE_LIMIT_UNAVAILABLE"))
        .andExpect(jsonPath("$.traceId").value(traceId));
  }

  private org.springframework.test.web.servlet.MockMvc mvc() {
    return MockMvcBuilders.standaloneSetup(new FailureController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .addFilters(new TraceIdFilter())
        .build();
  }

  @RestController
  static class FailureController {
    @GetMapping("/failure")
    String fail() {
      throw new IllegalStateException("boom");
    }

    @GetMapping("/limited")
    String limited() { throw new RateLimitedException(); }

    @GetMapping("/unavailable")
    String unavailable() { throw new RateLimitUnavailableException(); }
  }
}
