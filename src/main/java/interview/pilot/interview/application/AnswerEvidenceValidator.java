package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import interview.pilot.interview.domain.AnswerEvaluation;

/** Prevents model-produced evidence from being accepted without support in the current answer. */
public final class AnswerEvidenceValidator {

  public AnswerEvaluation validate(String answer, AnswerEvaluation evaluation) {
    String normalizedAnswer = EvidenceGrounding.normalize(answer);
    List<String> accepted = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    for (String evidence : evaluation.evidence()) {
      if (EvidenceGrounding.grounded(evidence, normalizedAnswer)) accepted.add(evidence);
      else rejected.add(evidence);
    }
    if (rejected.isEmpty()) return evaluation;

    Set<String> missing = new LinkedHashSet<>(evaluation.missingPoints());
    rejected.forEach(value -> missing.add("当前回答未提供可核验依据：" + value));
    return new AnswerEvaluation(
        evaluation.score(), evaluation.feedback(), accepted, List.copyOf(missing),
        evaluation.redFlags(), evaluation.suggestedDecision(),
        evaluation.referenceFacts(), evaluation.conflictFacts(),
        evaluation.evidenceAssessments());
  }
}
