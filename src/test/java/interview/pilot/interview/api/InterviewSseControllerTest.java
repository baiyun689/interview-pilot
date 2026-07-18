package interview.pilot.interview.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.exception.GlobalExceptionHandler;
import interview.pilot.interview.application.AnswerProcessingResult;
import interview.pilot.interview.application.InterviewQueryService;
import interview.pilot.interview.application.InterviewSseService;
import interview.pilot.interview.application.InterviewProcessingSla;
import interview.pilot.interview.application.InterviewTurnClaim;
import interview.pilot.interview.application.CreateInterviewService;
import interview.pilot.interview.application.SubmitAnswerService;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.AnswerAttemptStatus;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.ai.provider.AiProviderProperties;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.application.CurrentUserProvider;

class InterviewSseControllerTest {
  private SubmitAnswerService answers;
  private MockMvc mockMvc;
  private UUID sessionId;
  private UUID requestId;

  @BeforeEach
  void setUp() {
    answers = mock(SubmitAnswerService.class);
    mockMvc = mvc(task -> Thread.startVirtualThread(task));
    sessionId = UUID.randomUUID();
    requestId = UUID.randomUUID();
  }

  private MockMvc mvc(TaskExecutor executor) {
    var sla = new InterviewProcessingSla(
        new AiProviderProperties("", Map.of(), 1), Duration.ofSeconds(1), Duration.ofSeconds(1));
    var sse = new InterviewSseService(answers, executor, sla);
    CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    when(currentUser.require()).thenReturn(new CurrentUser(1L, new UUID(0L, 1L), "test@example.com", "Test"));
    var controller = new InterviewController(
        mock(CreateInterviewService.class), mock(InterviewQueryService.class), sse, currentUser);
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void streamsOnlyTypedEventsInStrictSuccessfulOrder() throws Exception {
    var decision = new InterviewDecision(
        NextStep.NEXT_TOPIC, DifficultyAdjustment.INCREASE, "Spring", "transactions",
        "Java covered", 0.91);
    var evaluation = new AnswerEvaluation(
        84, "Good answer", List.of("version check"), List.of("retry detail"), decision);
    InterviewTurnClaim claim = ownerClaim();
    when(answers.claim(any(), eq(sessionId), any())).thenReturn(claim);
    when(answers.processClaim(any(), eq(sessionId), any(), eq(claim))).thenReturn(new AnswerProcessingResult(
        sessionId, requestId, 1, evaluation, decision,
        new GeneratedQuestion("Explain transaction propagation.", "Spring"),
        Difficulty.HARD, SessionStatus.INTERVIEWING, false));

    MvcResult initial = mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"requestId\":\"" + requestId + "\",\"answer\":\" version check \"}"))
        .andExpect(status().isOk())
        .andReturn();
    MvcResult completed = mockMvc.perform(asyncDispatch(initial))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andReturn();

    String stream = completed.getResponse().getContentAsString();
    assertOrdered(stream, "event:ACCEPTED", "event:FEEDBACK", "event:DECISION",
        "event:NEXT_QUESTION", "event:COMPLETED");
    assertThat(stream).doesNotContain("event:ERROR");
    assertThat(stream).contains(
        "\"sessionId\":\"" + sessionId + "\"",
        "\"turnNo\":1",
        "\"requestId\":\"" + requestId + "\"",
        "\"score\":84.0",
        "\"difficulty\":\"HARD\"");
  }

  @Test
  void emitsOneSanitizedTypedErrorForAConflict() throws Exception {
    InterviewTurnClaim claim = ownerClaim();
    when(answers.claim(any(), eq(sessionId), any())).thenReturn(claim);
    when(answers.processClaim(any(), eq(sessionId), any(), eq(claim))).thenThrow(new BusinessException(
        "TURN_ALREADY_CLAIMED", "The current turn was claimed by another request",
        org.springframework.http.HttpStatus.CONFLICT));

    MvcResult initial = mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .content("{\"requestId\":\"" + requestId + "\",\"answer\":\"answer\"}"))
        .andExpect(status().isOk())
        .andReturn();
    String stream = mockMvc.perform(asyncDispatch(initial)).andReturn()
        .getResponse().getContentAsString();

    assertThat(stream).containsOnlyOnce("event:ERROR");
    assertThat(stream).contains(
        "\"type\":\"ERROR\"", "\"code\":\"TURN_ALREADY_CLAIMED\"",
        "\"retryable\":true");
    assertThat(stream).doesNotContain("secret", "stackTrace", "exception");
  }

  @Test
  void validatesBodyAndPreservesMethodAndMediaTypeContracts() throws Exception {
    mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"answer\":\" \"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/interviews/{id}/answers/stream", sessionId))
        .andExpect(status().isMethodNotAllowed());
    mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .contentType(MediaType.TEXT_PLAIN).content("answer"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void preclaimConflictUsesHttpErrorInsteadOfSyntheticTurnZeroEvent() throws Exception {
    when(answers.claim(any(), eq(sessionId), any())).thenThrow(new BusinessException(
        "TURN_ALREADY_CLAIMED", "The current turn was claimed by another request",
        org.springframework.http.HttpStatus.CONFLICT));

    mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\"" + requestId + "\",\"answer\":\"answer\"}"))
        .andExpect(status().isConflict());
    verify(answers, never()).processClaim(any(), any(), any(), any());
  }

  @Test
  void acceptedIsVisibleBeforeBlockedAiIsReleased() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      mockMvc = mvc(executor::execute);
      InterviewTurnClaim claim = ownerClaim();
      CountDownLatch processing = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      when(answers.claim(any(), eq(sessionId), any())).thenReturn(claim);
      when(answers.processClaim(any(), eq(sessionId), any(), eq(claim))).thenAnswer(invocation -> {
        processing.countDown();
        release.await(10, TimeUnit.SECONDS);
        return finishResult();
      });

      MvcResult initial = mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.TEXT_EVENT_STREAM)
              .content("{\"requestId\":\"" + requestId + "\",\"answer\":\"answer\"}"))
          .andReturn();
      assertThat(processing.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(initial.getResponse().getContentAsString()).contains("event:ACCEPTED");
      release.countDown();
      String completed = mockMvc.perform(asyncDispatch(initial)).andReturn()
          .getResponse().getContentAsString();
      assertOrdered(completed, "event:ACCEPTED", "event:FEEDBACK", "event:DECISION",
          "event:COMPLETED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void executorRejectionHappensBeforeClaimAndReturnsHttp503() throws Exception {
    mockMvc = mvc(task -> { throw new TaskRejectedException("full"); });

    mockMvc.perform(post("/api/interviews/{id}/answers/stream", sessionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"requestId\":\"" + requestId + "\",\"answer\":\"answer\"}"))
        .andExpect(status().isServiceUnavailable());
    verify(answers, never()).claim(any(), any(), any());
  }

  @Test
  void transportCompletionDoesNotCancelTheDatabaseOwnerWork() {
    class CapturingExecutor implements TaskExecutor {
      Runnable task;
      @Override public void execute(Runnable task) { this.task = task; }
    }
    var executor = new CapturingExecutor();
    var sla = new InterviewProcessingSla(
        new AiProviderProperties("", Map.of(), 1), Duration.ofSeconds(1), Duration.ofSeconds(1));
    var sse = new InterviewSseService(answers, executor, sla);
    InterviewTurnClaim claim = ownerClaim();
    when(answers.claim(any(), eq(sessionId), any())).thenReturn(claim);
    when(answers.processClaim(any(), eq(sessionId), any(), eq(claim))).thenReturn(finishResult());

    var emitter = sse.stream(sessionId, new SubmitAnswerRequest(requestId, "answer"));
    emitter.complete();
    executor.task.run();

    verify(answers).processClaim(any(), eq(sessionId), any(), eq(claim));
  }

  @Test
  void acceptedTransportFailureStillReleasesReservedWorkerToFinishOwner() {
    class CapturingExecutor implements TaskExecutor {
      Runnable task;
      @Override public void execute(Runnable task) { this.task = task; }
    }
    var executor = new CapturingExecutor();
    var sla = new InterviewProcessingSla(
        new AiProviderProperties("", Map.of(), 1), Duration.ofSeconds(1), Duration.ofSeconds(1));
    var service = new InterviewSseService(answers, executor, sla) {
      @Override protected org.springframework.web.servlet.mvc.method.annotation.SseEmitter
          createEmitter(long timeoutMillis) {
        return new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(timeoutMillis) {
          @Override
          public void send(SseEventBuilder builder) throws java.io.IOException {
            throw new java.io.IOException("disconnected");
          }
        };
      }
    };
    InterviewTurnClaim claim = ownerClaim();
    when(answers.claim(any(), eq(sessionId), any())).thenReturn(claim);
    when(answers.processClaim(any(), eq(sessionId), any(), eq(claim))).thenReturn(finishResult());

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> service.stream(sessionId, new SubmitAnswerRequest(requestId, "answer")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SSE connection closed");
    executor.task.run();

    verify(answers).processClaim(any(), eq(sessionId), any(), eq(claim));
  }

  private InterviewTurnClaim ownerClaim() {
    return new InterviewTurnClaim(
        InterviewTurnClaim.State.OWNER, 1L, 2L, 3L, requestId, "a".repeat(64),
        1, 0, 0, AnswerAttemptStatus.PROCESSING, null, null);
  }

  private AnswerProcessingResult finishResult() {
    var decision = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", "done", 0.9);
    return new AnswerProcessingResult(
        sessionId, requestId, 1,
        new AnswerEvaluation(80, "ok", List.of("e"), List.of(), decision),
        decision, null, Difficulty.MEDIUM, SessionStatus.EVALUATING, false);
  }

  private void assertOrdered(String stream, String... markers) {
    int previous = -1;
    for (String marker : markers) {
      int current = stream.indexOf(marker);
      assertThat(current).as(marker).isGreaterThan(previous);
      previous = current;
    }
  }
}
