package interview.pilot.interview.application;

import java.util.List;

public record ReportEvidence(
    int turnNo, String competency, double score, String feedback, List<String> evidence) {
  public ReportEvidence {
    if (turnNo < 1 || competency == null || competency.isBlank()
        || !Double.isFinite(score) || score < 0 || score > 100
        || feedback == null || feedback.isBlank() || evidence == null) {
      throw new IllegalArgumentException("Completed interview evidence is invalid");
    }
    competency = competency.trim();
    feedback = feedback.trim();
    evidence = evidence.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
  }
}
