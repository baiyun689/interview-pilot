package interview.pilot.interview.application;

import java.util.UUID;

import interview.pilot.interview.api.InterviewTurnView;
import interview.pilot.interview.domain.SessionStatus;

public record FixedAnswerResult(
    UUID sessionId,
    UUID requestId,
    int completedTurnNo,
    SessionStatus sessionStatus,
    InterviewTurnView nextTurn,
    boolean idempotentReplay) {
  public FixedAnswerResult asReplay() {
    return new FixedAnswerResult(
        sessionId, requestId, completedTurnNo, sessionStatus, nextTurn, true);
  }
}
