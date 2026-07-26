package interview.pilot.interview.application;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.InterviewStreamEvent;
import interview.pilot.interview.api.InterviewStreamEvent.AcceptedPayload;
import interview.pilot.interview.api.InterviewStreamEvent.CompletedPayload;
import interview.pilot.interview.api.InterviewStreamEvent.DecisionPayload;
import interview.pilot.interview.api.InterviewStreamEvent.ErrorPayload;
import interview.pilot.interview.api.InterviewStreamEvent.EventType;
import interview.pilot.interview.api.InterviewStreamEvent.FeedbackPayload;
import interview.pilot.interview.api.InterviewStreamEvent.NextQuestionPayload;
import interview.pilot.interview.api.SubmitAnswerRequest;

@Service
public class InterviewSseService {
  private final SubmitAnswerService answers;
  private final TaskExecutor taskExecutor;
  private final InterviewProcessingSla processingSla;

  public InterviewSseService(
      SubmitAnswerService answers,
      @Qualifier("interviewAnswerExecutor") TaskExecutor taskExecutor,
      InterviewProcessingSla processingSla) {
    this.answers = answers;
    this.taskExecutor = taskExecutor;
    this.processingSla = processingSla;
  }

  public SseEmitter stream(CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    SseEmitter emitter = createEmitter(processingSla.sseTimeoutMillis());
    AtomicBoolean transportTerminal = new AtomicBoolean();
    emitter.onTimeout(() -> transportTerminal.compareAndSet(false, true));
    emitter.onError(error -> transportTerminal.compareAndSet(false, true));
    emitter.onCompletion(() -> transportTerminal.compareAndSet(false, true));
    CompletableFuture<ClaimedWork> admittedWork = new CompletableFuture<>();
    try {
      taskExecutor.execute(() -> {
        ClaimedWork work = admittedWork.join();
        if (work != null) {
          process(emitter, transportTerminal, work.user(), work.sessionId(), work.request(), work.claim());
        }
      });
    } catch (TaskRejectedException exception) {
      throw executorBusy();
    } catch (RejectedExecutionException exception) {
      throw executorBusy();
    }

    InterviewTurnClaim claim;
    try {
      claim = answers.claim(user, sessionId, request);
    } catch (RuntimeException exception) {
      admittedWork.complete(null);
      throw exception;
    }
    try {
      send(emitter, transportTerminal, new InterviewStreamEvent(
          EventType.ACCEPTED, sessionId, claim.turnNo(),
          new AcceptedPayload(request.requestId(), !claim.owner())));
    } finally {
      // Capacity is already reserved, so durable owner processing starts even if transport fails.
      admittedWork.complete(new ClaimedWork(user, sessionId, request, claim));
    }
    return emitter;
  }

  private void process(
      SseEmitter emitter,
      AtomicBoolean transportTerminal,
      CurrentUser user,
      UUID sessionId,
      SubmitAnswerRequest request,
      InterviewTurnClaim claim) {
    try {
      AnswerProcessingResult result = answers.processClaim(user, sessionId, request, claim);
      send(emitter, transportTerminal, new InterviewStreamEvent(
          EventType.FEEDBACK, sessionId, result.turnNo(),
          new FeedbackPayload(
              result.evaluation().score(), result.evaluation().feedback(),
              result.evaluation().evidence(), result.evaluation().missingPoints())));
      send(emitter, transportTerminal, new InterviewStreamEvent(
          EventType.DECISION, sessionId, result.turnNo(), new DecisionPayload(result.decision())));
      if (result.nextQuestion() != null) {
        send(emitter, transportTerminal, new InterviewStreamEvent(
            EventType.NEXT_QUESTION, sessionId, result.turnNo(),
            new NextQuestionPayload(
                result.nextQuestion().question(), result.nextQuestion().targetCompetency(),
                result.nextDifficulty())));
      }
      send(emitter, transportTerminal, new InterviewStreamEvent(
          EventType.COMPLETED, sessionId, result.turnNo(),
          new CompletedPayload(result.sessionStatus())));
      complete(emitter, transportTerminal);
    } catch (BusinessException exception) {
      safeError(emitter, transportTerminal, sessionId, claim.turnNo(),
          exception.code(), exception.getMessage(), exception.status().is5xxServerError()
              || exception.status().value() == 409);
    } catch (RuntimeException exception) {
      safeError(emitter, transportTerminal, sessionId, claim.turnNo(),
          "ANSWER_STREAM_FAILED", "Answer processing failed; retry shortly", true);
    }
  }

  private void safeError(
      SseEmitter emitter,
      AtomicBoolean transportTerminal,
      UUID sessionId,
      int turnNo,
      String code,
      String message,
      boolean retryable) {
    try {
      send(emitter, transportTerminal, new InterviewStreamEvent(
          EventType.ERROR, sessionId, turnNo,
          new ErrorPayload(code, message, retryable)));
      complete(emitter, transportTerminal);
    } catch (RuntimeException ignored) {
      if (transportTerminal.compareAndSet(false, true)) {
        emitter.completeWithError(new IllegalStateException("SSE connection closed"));
      }
    }
  }

  private void send(
      SseEmitter emitter, AtomicBoolean transportTerminal, InterviewStreamEvent event) {
    if (transportTerminal.get()) return;
    try {
      emitter.send(SseEmitter.event().name(event.type().name()).data(event));
    } catch (IOException exception) {
      throw new IllegalStateException("SSE connection closed");
    }
  }

  private void complete(SseEmitter emitter, AtomicBoolean transportTerminal) {
    if (transportTerminal.compareAndSet(false, true)) emitter.complete();
  }

  private BusinessException executorBusy() {
    return new BusinessException(
        "ANSWER_EXECUTOR_BUSY", "Answer processing is temporarily busy; retry shortly",
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  protected SseEmitter createEmitter(long timeoutMillis) {
    return new SseEmitter(timeoutMillis);
  }

  private record ClaimedWork(
      CurrentUser user, UUID sessionId, SubmitAnswerRequest request, InterviewTurnClaim claim) {}
}
