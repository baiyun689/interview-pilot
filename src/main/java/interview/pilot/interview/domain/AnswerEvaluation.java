package interview.pilot.interview.domain;

import java.util.List;
import java.util.Objects;

public record AnswerEvaluation(
    double score,
    String feedback,
    List<String> evidence,
    List<String> missingPoints,
    List<String> redFlags,
    InterviewDecision suggestedDecision,
    List<ReferenceFact> referenceFacts,
    List<ReferenceFact> conflictFacts,
    List<EvidenceAssessment> evidenceAssessments) {

  public AnswerEvaluation {
    if (!Double.isFinite(score) || score < 0 || score > 100) {
      throw new IllegalArgumentException("score must be finite and between 0 and 100");
    }
    feedback = normalize(feedback);
    evidence = normalizedItems(evidence, "evidence");
    missingPoints = normalizedItems(missingPoints, "missingPoints");
    redFlags = redFlags == null ? List.of() : normalizedItems(redFlags, "redFlags");
    suggestedDecision = Objects.requireNonNull(suggestedDecision, "suggestedDecision must not be null");
    referenceFacts = referenceFacts == null ? List.of() : List.copyOf(referenceFacts);
    conflictFacts = conflictFacts == null ? List.of() : List.copyOf(conflictFacts);
    evidenceAssessments = evidenceAssessments == null ? List.of() : List.copyOf(evidenceAssessments);
  }

  public AnswerEvaluation(
      double score, String feedback, List<String> evidence, List<String> missingPoints,
      List<String> redFlags, InterviewDecision suggestedDecision,
      List<ReferenceFact> referenceFacts, List<ReferenceFact> conflictFacts) {
    this(score, feedback, evidence, missingPoints, redFlags, suggestedDecision,
        referenceFacts, conflictFacts, List.of());
  }

  public AnswerEvaluation(
      double score, String feedback, List<String> evidence, List<String> missingPoints,
      List<String> redFlags, InterviewDecision suggestedDecision) {
    this(score, feedback, evidence, missingPoints, redFlags, suggestedDecision, List.of(), List.of());
  }

  public AnswerEvaluation(
      double score, String feedback, List<String> evidence, List<String> missingPoints,
      InterviewDecision suggestedDecision) {
    this(score, feedback, evidence, missingPoints, List.of(), suggestedDecision);
  }

  public record ReferenceFact(String sourceId, String fact) {
    public ReferenceFact {
      sourceId = required(sourceId, "sourceId");
      fact = required(fact, "fact");
    }

    private static String required(String value, String name) {
      String normalized = value == null ? "" : value.trim();
      if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
      return normalized;
    }
  }

  /**
   * 模型对照当前轮 evidence 目标逐条给出的证据判定。宽松归化：AI 输出劣质条目时不让整次
   * 评估解析失败；evidenceId 合法性由 EvidenceAssessmentValidator 依据目标集校验。
   */
  public record EvidenceAssessment(String evidenceId, boolean observed, String claim) {
    public EvidenceAssessment {
      evidenceId = evidenceId == null ? "" : evidenceId.trim();
      claim = claim == null ? "" : claim.trim();
      if (observed && claim.isEmpty()) observed = false;
      if (!observed) claim = "";
    }
  }

  private static List<String> normalizedItems(List<String> values, String name) {
    Objects.requireNonNull(values, name + " must not be null");
    return values.stream()
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
