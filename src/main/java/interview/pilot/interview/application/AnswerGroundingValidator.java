package interview.pilot.interview.application;

import java.util.Set;
import java.util.stream.Collectors;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.grounding.GroundingUsePolicy;
import interview.pilot.interview.strategy.TurnDirective;

public final class AnswerGroundingValidator {
  public AnswerEvaluation validate(AnswerEvaluation evaluation, RagContextSnapshot snapshot) {
    return validate(evaluation, snapshot, null);
  }

  public AnswerEvaluation validate(
      AnswerEvaluation evaluation, RagContextSnapshot snapshot, TurnDirective directive) {
    if (directive != null && (!evaluation.referenceFacts().isEmpty()
        || !evaluation.conflictFacts().isEmpty())
        && !GroundingUsePolicy.allowsFactVerification(directive)) {
      throw new IllegalArgumentException("Fact verification is not allowed by grounding policy");
    }
    Set<String> allowed = snapshot.chunks().stream()
        .map(RagContextSnapshot.Chunk::pointId).collect(Collectors.toSet());
    boolean invalid = java.util.stream.Stream.concat(
        evaluation.referenceFacts().stream(), evaluation.conflictFacts().stream())
        .map(AnswerEvaluation.ReferenceFact::sourceId)
        .anyMatch(sourceId -> !allowed.contains(sourceId));
    if (invalid) {
      throw new IllegalArgumentException("Reference facts must belong to current grounding snapshot");
    }
    return evaluation;
  }
}
