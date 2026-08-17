package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.domain.AnswerEvaluation;

public record ReportEvidence(
    int turnNo, String competency, double score, String feedback, List<String> evidence,
    String stageId, InterviewQuestionMode questionMode, List<String> evidenceTargets,
    String planRationale, GroundingMode groundingMode, List<String> evidenceRefs,
    List<SourceReference> sources, List<AnswerEvaluation.ReferenceFact> referenceFacts,
    List<AnswerEvaluation.ReferenceFact> conflictFacts) {
  public ReportEvidence {
    if (turnNo < 1 || competency == null || competency.isBlank()
        || !Double.isFinite(score) || score < 0 || score > 100
        || feedback == null || feedback.isBlank() || evidence == null) {
      throw new IllegalArgumentException("Completed interview evidence is invalid");
    }
    competency = competency.trim();
    feedback = feedback.trim();
    evidence = evidence.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    stageId = stageId == null ? "legacy" : stageId;
    questionMode = questionMode == null ? InterviewQuestionMode.PROJECT : questionMode;
    evidenceTargets = evidenceTargets == null ? List.of() : List.copyOf(evidenceTargets);
    planRationale = planRationale == null ? "" : planRationale;
    groundingMode = groundingMode == null ? GroundingMode.SKILL_GENERAL : groundingMode;
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    sources = sources == null ? List.of() : List.copyOf(sources);
    referenceFacts = referenceFacts == null ? List.of() : List.copyOf(referenceFacts);
    conflictFacts = conflictFacts == null ? List.of() : List.copyOf(conflictFacts);
  }

  public ReportEvidence(
      int turnNo, String competency, double score, String feedback, List<String> evidence) {
    this(turnNo, competency, score, feedback, evidence, "legacy",
        InterviewQuestionMode.PROJECT, List.of(), "", GroundingMode.SKILL_GENERAL,
        List.of(), List.of(), List.of(), List.of());
  }

  public record SourceReference(
      String sourceId, String filename, int documentRevision,
      String section, Integer pageNumber, double score) {}
}
