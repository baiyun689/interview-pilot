package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.AnswerEvaluation.EvidenceAssessment;

/**
 * 校验模型逐条输出的证据判定：evidenceId 必须属于当前轮证据目标集（不能由模型自由发明），
 * observed 的 claim 必须能在候选人回答中回溯，同一 evidenceId 合并时 observed 优先。
 */
public final class EvidenceAssessmentValidator {

  public AnswerEvaluation validate(
      String answer, AnswerEvaluation evaluation, List<String> evidenceTargets) {
    if (evaluation.evidenceAssessments().isEmpty()) return evaluation;
    String normalizedAnswer = EvidenceGrounding.normalize(answer);
    List<EvidenceAssessment> cleaned = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (EvidenceAssessment assessment : evaluation.evidenceAssessments()) {
      if (!evidenceTargets.contains(assessment.evidenceId())) continue;
      if (assessment.observed()
          && !EvidenceGrounding.grounded(assessment.claim(), normalizedAnswer)) {
        assessment = new EvidenceAssessment(assessment.evidenceId(), false, "");
      }
      if (!seen.add(assessment.evidenceId())) {
        if (!assessment.observed()) continue;
        int index = indexOf(cleaned, assessment.evidenceId());
        if (!cleaned.get(index).observed()) cleaned.set(index, assessment);
        continue;
      }
      cleaned.add(assessment);
    }
    if (cleaned.size() == evaluation.evidenceAssessments().size()
        && cleaned.equals(evaluation.evidenceAssessments())) {
      return evaluation;
    }
    return new AnswerEvaluation(
        evaluation.score(), evaluation.feedback(), evaluation.evidence(),
        evaluation.missingPoints(), evaluation.redFlags(), evaluation.suggestedDecision(),
        evaluation.referenceFacts(), evaluation.conflictFacts(), cleaned);
  }

  private int indexOf(List<EvidenceAssessment> assessments, String evidenceId) {
    for (int index = 0; index < assessments.size(); index++) {
      if (assessments.get(index).evidenceId().equals(evidenceId)) return index;
    }
    return -1;
  }
}
