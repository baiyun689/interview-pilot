package interview.pilot.interview.api;

import java.util.UUID;

import interview.pilot.interview.domain.SessionStatus;

public record InterviewStreamEvent(
    EventType type,
    UUID sessionId,
    int turnNo,
    Payload payload) {
  public enum EventType { ACCEPTED, PROCESSING, RESULT, ERROR }
  public sealed interface Payload permits AcceptedPayload, ProcessingPayload, ResultPayload, ErrorPayload { }
  public record AcceptedPayload(UUID requestId, boolean replayed) implements Payload { }
  public record ProcessingPayload(String state) implements Payload { }
  public record ResultPayload(
      int completedTurnNo, SessionStatus status, InterviewTurnView nextTurn,
      boolean idempotentReplay) implements Payload { }
  public record ErrorPayload(String code, String message, boolean retryable) implements Payload { }
}
