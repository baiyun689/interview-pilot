package interview.pilot.interview.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import interview.pilot.interview.application.CreateInterviewService;
import interview.pilot.interview.application.InterviewQueryService;
import interview.pilot.interview.application.InterviewSseService;
import jakarta.validation.Valid;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;

@RestController
@RequestMapping("/api/interviews")
public class InterviewController {
  private final CreateInterviewService createService;
  private final InterviewQueryService queryService;
  private final InterviewSseService sseService;

  public InterviewController(
      CreateInterviewService createService,
      InterviewQueryService queryService,
      InterviewSseService sseService) {
    this.createService = createService;
    this.queryService = queryService;
    this.sseService = sseService;
  }

  @PostMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  @ResponseStatus(HttpStatus.CREATED)
  public InterviewSessionResponse create(@Valid @RequestBody CreateInterviewRequest request) {
    return createService.create(request);
  }

  @GetMapping("/{sessionId}")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public InterviewSessionResponse get(@PathVariable UUID sessionId) {
    return queryService.get(sessionId);
  }

  @GetMapping
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public List<InterviewHistoryResponse> list() {
    return queryService.list();
  }

  @GetMapping("/{sessionId}/report")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public ResponseEntity<?> report(@PathVariable UUID sessionId) {
    var result = queryService.report(sessionId);
    return ResponseEntity.status(result.status()).body(result.body());
  }

  @PostMapping(
      path = "/{sessionId}/answers/stream",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @RateLimit(scope = RateLimitScope.IP, capacity = 30, expensive = true)
  @RateLimit(scope = RateLimitScope.SESSION, capacity = 12, expensive = true)
  public SseEmitter submitAnswer(
      @PathVariable UUID sessionId,
      @Valid @RequestBody SubmitAnswerRequest request) {
    return sseService.stream(sessionId, request);
  }
}
