package interview.pilot.interview.application;

import java.util.Set;
import java.util.stream.Collectors;

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
    Set<String> allowed = snapshot.chunks().stream()
        .map(RagContextSnapshot.Chunk::pointId).collect(Collectors.toSet());
    if (!allowed.containsAll(question.evidenceRefs())) {
      throw new IllegalArgumentException("Question evidenceRefs must belong to current grounding snapshot");
    }
    return new GeneratedQuestion(
        question.question(), question.targetCompetency(), GroundingMode.KNOWLEDGE_ASSISTED,
        question.evidenceRefs());
  }
}
