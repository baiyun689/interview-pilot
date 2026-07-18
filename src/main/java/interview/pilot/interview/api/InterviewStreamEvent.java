package interview.pilot.interview.api;

import java.util.List;
import java.util.UUID;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.SessionStatus;

public record InterviewStreamEvent(
    EventType type,
    UUID sessionId,
    int turnNo,
    Payload payload) {

  public enum EventType {
    ACCEPTED,
    FEEDBACK,
    DECISION,
    NEXT_QUESTION,
    COMPLETED,
    ERROR
  }

  public sealed interface Payload permits
      AcceptedPayload, FeedbackPayload, DecisionPayload, NextQuestionPayload,
      CompletedPayload, ErrorPayload {}

  public record AcceptedPayload(UUID requestId, boolean replayed) implements Payload {}

  public record FeedbackPayload(
      double score, String feedback, List<String> evidence, List<String> missingPoints)
      implements Payload {
    public FeedbackPayload {
      evidence = List.copyOf(evidence);
      missingPoints = List.copyOf(missingPoints);
    }
  }

  public record DecisionPayload(InterviewDecision decision) implements Payload {}

  public record NextQuestionPayload(
      String question, String targetCompetency, Difficulty difficulty) implements Payload {}

  public record CompletedPayload(SessionStatus status) implements Payload {}

  public record ErrorPayload(String code, String message, boolean retryable) implements Payload {}
}
