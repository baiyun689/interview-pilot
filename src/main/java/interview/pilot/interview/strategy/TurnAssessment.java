package interview.pilot.interview.strategy;

import java.util.ArrayList;
import java.util.List;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.AnswerEvaluation.EvidenceAssessment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlanItem;

/**
 * 把本轮评价转换成 Java 可执行的证据变化。
 * newEvidence 只来自已校验的逐条判定；missingEvidence 由能力证据目标集计算，不由模型发明；
 * suggestedDecision 只是候选，最终动作由 Strategy 决定。
 */
public record TurnAssessment(
    String competency,
    List<ObservedEvidence> newEvidence,
    List<String> missingEvidence,
    List<String> redFlags,
    double score,
    boolean legacyTurn,
    boolean legacyCoverage,
    InterviewDecision suggestedDecision) {

  public TurnAssessment {
    competency = competency == null ? "" : competency.trim();
    newEvidence = List.copyOf(newEvidence);
    missingEvidence = List.copyOf(missingEvidence);
    redFlags = List.copyOf(redFlags);
  }

  public static TurnAssessment of(
      AnswerEvaluation evaluation, InterviewPlanItem item, int turnNo) {
    boolean legacyTurn = evaluation.evidenceAssessments().isEmpty();
    List<ObservedEvidence> observed = new ArrayList<>();
    for (EvidenceAssessment assessment : evaluation.evidenceAssessments()) {
      if (!assessment.observed() || !item.evidenceTargets().contains(assessment.evidenceId())) {
        continue;
      }
      observed.add(new ObservedEvidence(assessment.evidenceId(), assessment.claim(), turnNo));
    }
    List<String> missing = item.evidenceTargets().stream()
        .filter(target -> observed.stream().noneMatch(evidence -> evidence.evidenceId().equals(target)))
        .toList();
    boolean legacyCoverage = legacyTurn
        && evaluation.score() >= 60
        && !evaluation.evidence().isEmpty();
    return new TurnAssessment(
        item.competency(), observed, missing, evaluation.redFlags(),
        evaluation.score(), legacyTurn, legacyCoverage, evaluation.suggestedDecision());
  }
}
