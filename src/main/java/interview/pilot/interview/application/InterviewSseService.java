package interview.pilot.interview.application;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.InterviewStreamEvent;
import interview.pilot.interview.api.InterviewStreamEvent.AcceptedPayload;
import interview.pilot.interview.api.InterviewStreamEvent.ErrorPayload;
import interview.pilot.interview.api.InterviewStreamEvent.EventType;
import interview.pilot.interview.api.InterviewStreamEvent.ProcessingPayload;
import interview.pilot.interview.api.InterviewStreamEvent.ResultPayload;
import interview.pilot.interview.api.SubmitAnswerRequest;

@Service
public class InterviewSseService {
  private final FixedAnswerService answers;
  private final TaskExecutor executor;
  private final InterviewProcessingSla processingSla;

  public InterviewSseService(
      FixedAnswerService answers,
      @Qualifier("interviewAnswerExecutor") TaskExecutor executor,
      InterviewProcessingSla processingSla) {
    this.answers = answers;
    this.executor = executor;
    this.processingSla = processingSla;
  }

  public SseEmitter stream(CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    SseEmitter emitter = createEmitter(processingSla.sseTimeoutMillis());
    AtomicBoolean terminal = new AtomicBoolean();
    emitter.onTimeout(() -> terminal.compareAndSet(false, true));
    emitter.onError(error -> terminal.compareAndSet(false, true));
    emitter.onCompletion(() -> terminal.compareAndSet(false, true));
    CompletableFuture<FixedAnswerClaim> admitted = new CompletableFuture<>();
    try {
      executor.execute(() -> process(emitter, terminal, sessionId, admitted.join()));
    } catch (RejectedExecutionException exception) {
      throw new BusinessException(
          "ANSWER_EXECUTOR_BUSY", "Answer processing is temporarily busy; retry shortly",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
    FixedAnswerClaim claim;
    try {
      claim = answers.claim(user, sessionId, request);
    } catch (RuntimeException exception) {
      admitted.complete(null);
      throw exception;
    }
    try {
      send(emitter, terminal, new InterviewStreamEvent(
          EventType.ACCEPTED, sessionId, claim.turnNo(),
          new AcceptedPayload(request.requestId(), !claim.owner())));
    } finally {
      admitted.complete(claim);
    }
    return emitter;
  }

  private void process(
      SseEmitter emitter, AtomicBoolean terminal, UUID sessionId, FixedAnswerClaim claim) {
    if (claim == null) return;
    try {
      send(emitter, terminal, new InterviewStreamEvent(
          EventType.PROCESSING, sessionId, claim.turnNo(),
          new ProcessingPayload("GENERATING_NEXT_TURN")));
      FixedAnswerResult result = answers.process(claim);
      send(emitter, terminal, new InterviewStreamEvent(
          EventType.RESULT, sessionId, result.completedTurnNo(),
          new ResultPayload(result.completedTurnNo(), result.sessionStatus(),
              result.nextTurn(), result.idempotentReplay())));
      complete(emitter, terminal);
    } catch (BusinessException exception) {
      error(emitter, terminal, sessionId, claim.turnNo(), exception.code(),
          exception.getMessage(), exception.status().is5xxServerError());
    } catch (RuntimeException exception) {
      error(emitter, terminal, sessionId, claim.turnNo(), "ANSWER_STREAM_FAILED",
          "Answer processing failed; retry shortly", true);
    }
  }

  private void error(
      SseEmitter emitter, AtomicBoolean terminal, UUID sessionId, int turnNo,
      String code, String message, boolean retryable) {
    send(emitter, terminal, new InterviewStreamEvent(
        EventType.ERROR, sessionId, turnNo, new ErrorPayload(code, message, retryable)));
    complete(emitter, terminal);
  }

  private void send(SseEmitter emitter, AtomicBoolean terminal, InterviewStreamEvent event) {
    if (terminal.get()) return;
    try {
      emitter.send(SseEmitter.event().name(event.type().name()).data(event));
    } catch (IOException ignored) {
      // Durable owner work continues after the transport disconnects.
    }
  }

  private void complete(SseEmitter emitter, AtomicBoolean terminal) {
    if (terminal.compareAndSet(false, true)) emitter.complete();
  }

  protected SseEmitter createEmitter(long timeoutMillis) {
    return new SseEmitter(timeoutMillis);
  }
}
