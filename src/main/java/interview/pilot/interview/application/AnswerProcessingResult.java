package interview.pilot.interview.application;

import java.util.UUID;
import java.util.Objects;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.SessionStatus;

public record AnswerProcessingResult(
    UUID sessionId,
    UUID requestId,
    int turnNo,
    AnswerEvaluation evaluation,
    InterviewDecision decision,
    GeneratedQuestion nextQuestion,
    Difficulty nextDifficulty,
    SessionStatus sessionStatus,
    boolean replayed) {

  public AnswerProcessingResult {
    sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    requestId = Objects.requireNonNull(requestId, "requestId must not be null");
    if (turnNo < 1) throw new IllegalArgumentException("turnNo must be positive");
    evaluation = Objects.requireNonNull(evaluation, "evaluation must not be null");
    decision = Objects.requireNonNull(decision, "decision must not be null");
    if (decision.nextStep() == null || decision.difficultyAdjustment() == null) {
      throw new IllegalArgumentException("decision is invalid");
    }
    nextDifficulty = Objects.requireNonNull(nextDifficulty, "nextDifficulty must not be null");
    sessionStatus = Objects.requireNonNull(sessionStatus, "sessionStatus must not be null");
    if ((sessionStatus == SessionStatus.EVALUATING) != (nextQuestion == null)) {
      throw new IllegalArgumentException("nextQuestion does not match session status");
    }
  }

  public AnswerProcessingResult asReplay() {
    return new AnswerProcessingResult(
        sessionId, requestId, turnNo, evaluation, decision, nextQuestion,
        nextDifficulty, sessionStatus, true);
  }
}
