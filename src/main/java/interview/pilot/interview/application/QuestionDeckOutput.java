package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;

public record QuestionDeckOutput(int schemaVersion, List<Question> questions) {
  public QuestionDeckOutput {
    questions = questions == null ? List.of() : List.copyOf(questions);
  }

  public record Question(
      InterviewPhase phase,
      int sequence,
      String topic,
      String question,
      List<String> focusPoints,
      GroundingMode groundingMode,
      List<String> evidenceRefs,
      String fallbackFollowUp) {
    public Question {
      focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    }
  }
}
