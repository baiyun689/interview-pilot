package interview.pilot.interview.application;

import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.grounding.GroundingUsePolicy;
import interview.pilot.interview.strategy.TurnDirective;

public final class QuestionGroundingValidator {
  public GeneratedQuestion validate(GeneratedQuestion question, RagContextSnapshot snapshot) {
    return validate(question, snapshot, null);
  }

  public GeneratedQuestion validate(
      GeneratedQuestion question, RagContextSnapshot snapshot, TurnDirective directive) {
    if (question.evidenceRefs().isEmpty()) {
      return new GeneratedQuestion(
          question.question(), question.targetCompetency(), GroundingMode.SKILL_GENERAL,
          java.util.List.of());
    }
    if (directive != null && !GroundingUsePolicy.allowsQuestionGeneration(directive)) {
      throw new IllegalArgumentException("Question generation is not allowed by grounding policy");
    }
    snapshot.requireCurrentSources(question.evidenceRefs());
    return new GeneratedQuestion(
        question.question(), question.targetCompetency(), GroundingMode.KNOWLEDGE_ASSISTED,
        question.evidenceRefs());
  }
}
