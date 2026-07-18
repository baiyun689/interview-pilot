package interview.pilot.interview.application;

import java.util.UUID;

import interview.pilot.interview.domain.TurnStatus;

public record InterviewTurnClaim(
    State state,
    Long sessionDatabaseId,
    Long turnId,
    Long attemptId,
    UUID requestId,
    String answerHash,
    int turnNo,
    long ownerVersion,
    long attemptVersion,
    interview.pilot.interview.domain.AnswerAttemptStatus attemptStatus,
    String persistedSnapshot,
    String processingError) {

  public enum State {
    OWNER,
    PROCESSING,
    COMPLETED,
    FAILED
  }

  public boolean owner() {
    return state == State.OWNER;
  }
}
