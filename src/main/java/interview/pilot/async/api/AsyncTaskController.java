package interview.pilot.async.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import interview.pilot.async.application.AsyncTaskService;
import jakarta.servlet.http.HttpServletRequest;
import interview.pilot.common.ratelimit.RateLimit;
import interview.pilot.common.ratelimit.RateLimitScope;

@RestController
@RequestMapping("/api/tasks")
public class AsyncTaskController {
  private final AsyncTaskService service;

  public AsyncTaskController(AsyncTaskService service) {
    this.service = service;
  }

  @GetMapping("/{taskId}")
  @RateLimit(scope = RateLimitScope.IP, capacity = 120)
  public AsyncTaskResponse get(@PathVariable UUID taskId) {
    return service.get(taskId);
  }

  @PostMapping("/{taskId}/retry")
  @RateLimit(scope = RateLimitScope.IP, capacity = 10, expensive = true)
  @RateLimit(scope = RateLimitScope.USER, capacity = 10, expensive = true)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AsyncTaskResponse retry(@PathVariable UUID taskId, HttpServletRequest request) {
    return service.retry(taskId, safeTraceId(request));
  }

  private UUID safeTraceId(HttpServletRequest request) {
    Object value = request.getAttribute("traceId");
    if (value != null) {
      try {
        return UUID.fromString(value.toString());
      } catch (IllegalArgumentException ignored) {
        // Never reflect or log an untrusted trace value.
      }
    }
    return UUID.randomUUID();
  }
}
