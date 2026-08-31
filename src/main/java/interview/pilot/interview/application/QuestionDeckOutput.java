package interview.pilot.interview.application;

import java.util.List;
import java.util.Objects;

import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.QuestionDeck;

public record QuestionDeckOutput(List<QuestionOutput> questions) {
  public QuestionDeckOutput {
    questions = questions == null ? List.of() : List.copyOf(questions);
    if (questions.isEmpty()) throw new IllegalArgumentException("questions must not be empty");
  }

  public QuestionDeck toDomain() {
    return new QuestionDeck(questions.stream().map(QuestionOutput::toDomain).toList());
  }

  public record QuestionOutput(
      String question, String targetCompetency,
      GroundingMode groundingMode, List<String> evidenceRefs) {
    public QuestionOutput {
      Objects.requireNonNull(groundingMode, "groundingMode is required");
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
      if ((groundingMode == GroundingMode.SKILL_GENERAL && !evidenceRefs.isEmpty())
          || (groundingMode == GroundingMode.KNOWLEDGE_ASSISTED && evidenceRefs.isEmpty())) {
        throw new IllegalArgumentException("groundingMode and evidenceRefs are inconsistent");
      }
      new GeneratedQuestion(question, targetCompetency, groundingMode, evidenceRefs);
    }

    GeneratedQuestion toDomain() {
      return new GeneratedQuestion(question, targetCompetency, groundingMode, evidenceRefs);
    }
  }
}
