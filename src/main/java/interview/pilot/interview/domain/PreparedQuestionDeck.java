package interview.pilot.interview.domain;

import java.util.List;

public record PreparedQuestionDeck(List<PreparedQuestion> questions) {
  public PreparedQuestionDeck {
    questions = List.copyOf(questions);
  }

  public List<PreparedQuestion> questionsFor(InterviewPhase phase) {
    return questions.stream().filter(question -> question.phase() == phase).toList();
  }

  public record PreparedQuestion(
      InterviewPhase phase,
      int sequence,
      String topic,
      String question,
      List<String> focusPoints,
      GroundingMode groundingMode,
      List<String> evidenceRefs,
      String fallbackFollowUp) {
    public PreparedQuestion {
      focusPoints = List.copyOf(focusPoints);
      evidenceRefs = List.copyOf(evidenceRefs);
    }
  }
}
