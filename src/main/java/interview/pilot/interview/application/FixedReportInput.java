package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.InterviewBriefSnapshot;

public record FixedReportInput(
    InterviewBriefSnapshot brief,
    List<TurnEvidence> completedTurns) {
  public FixedReportInput {
    completedTurns = List.copyOf(completedTurns);
  }

  public record TurnEvidence(
      int turnNo,
      String phase,
      String questionType,
      String question,
      String answer,
      Object ragSnapshot,
      List<String> permittedSourceIds) {
    public TurnEvidence {
      permittedSourceIds = List.copyOf(permittedSourceIds);
    }
  }
}
