package interview.pilot.async.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.async.application.AsyncTaskService;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.exception.GlobalExceptionHandler;

class AsyncTaskControllerTest {
  private AsyncTaskService service;
  private CurrentUserProvider currentUser;
  private MockMvc mvc;
  private final CurrentUser user = new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User");

  @BeforeEach
  void setUp() {
    service = mock(AsyncTaskService.class);
    currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(user);
    mvc = MockMvcBuilders.standaloneSetup(new AsyncTaskController(service, currentUser))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void exposesSafeTaskDtoAndAcceptsRetry() throws Exception {
    UUID taskId = UUID.randomUUID();
    var failed = response(taskId, AsyncTaskStatus.DEAD, "Retries exhausted");
    var pending = response(taskId, AsyncTaskStatus.PENDING, null);
    when(service.get(user, taskId)).thenReturn(failed);
    when(service.retry(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq(taskId), any(UUID.class)))
        .thenReturn(pending);

    mvc.perform(get("/api/tasks/{id}", taskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskId").value(taskId.toString()))
        .andExpect(jsonPath("$.status").value("DEAD"))
        .andExpect(jsonPath("$.bizKey").doesNotExist())
        .andExpect(jsonPath("$.payloadSnapshot").doesNotExist());
    mvc.perform(post("/api/tasks/{id}/retry", taskId))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void illegalRetryIsConflictAndFrameworkMethodsRemainSemantic() throws Exception {
    UUID taskId = UUID.randomUUID();
    when(service.retry(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq(taskId), any(UUID.class)))
        .thenThrow(new BusinessException(
            "TASK_NOT_RETRYABLE", "Only failed or dead tasks can be retried", HttpStatus.CONFLICT));

    mvc.perform(post("/api/tasks/{id}/retry", taskId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_NOT_RETRYABLE"));
    mvc.perform(patch("/api/tasks/{id}", taskId)).andExpect(status().isMethodNotAllowed());
  }

  private AsyncTaskResponse response(UUID taskId, AsyncTaskStatus status, String error) {
    return new AsyncTaskResponse(
        taskId, AsyncTaskType.INTERVIEW_EVALUATION, status, 4, 7, error,
        Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:00:01Z"));
  }
}
