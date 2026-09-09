package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.InterviewBriefSnapshot;

public record FixedReportInput(
    InterviewBriefSnapshot brief,
    List<TurnEvidence> completedTurns,
    boolean recruitment,
    int expectedMainQuestions,
    List<UnassessedQuestion> unassessedQuestions) {
  public FixedReportInput {
    completedTurns = List.copyOf(completedTurns);
    unassessedQuestions = List.copyOf(unassessedQuestions);
  }
  public FixedReportInput(InterviewBriefSnapshot brief, List<TurnEvidence> completedTurns) {
    this(brief, completedTurns, false, brief.totalMainQuestionCount(), List.of());
  }
  public record UnassessedQuestion(String question, String reason) {}

  /**
   * Per-turn evidence. {@code evaluation} is the asynchronously produced structured judgment when
   * it reached a terminal success state, and null when the report must fall back to judging the
   * raw question/answer text (evaluation disabled / failed / still missing after the barrier).
   */
  public record TurnEvidence(
      int turnNo,
      String phase,
      String questionType,
      String question,
      String answer,
      Object ragSnapshot,
      List<String> permittedSourceIds,
      AnswerEvaluation evaluation) {
    public TurnEvidence {
      permittedSourceIds = List.copyOf(permittedSourceIds);
    }
  }
}
