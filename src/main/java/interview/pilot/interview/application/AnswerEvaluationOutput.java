package interview.pilot.interview.application;

import java.util.List;

/**
 * Raw structured output of the answer-evaluation LLM. The model is treated as untrusted: it never
 * decides grounding mode, source validity, timestamp or final status — {@code
 * AnswerEvaluationAssembler} adjudicates those against the card's real snapshot.
 */
public record AnswerEvaluationOutput(
    Integer schemaVersion,
    Integer score,
    List<String> coveredPoints,
    List<MissingPointData> missingPoints,
    List<String> factualIssues,
    List<String> citedSourceIds) {

  // Deliberately lenient: untrusted model output may contain null/blank elements, which the
  // AnswerEvaluationAssembler filters. List.copyOf would reject a null element outright.
  public AnswerEvaluationOutput {
    coveredPoints = coveredPoints == null ? List.of() : coveredPoints;
    missingPoints = missingPoints == null ? List.of() : missingPoints;
    factualIssues = factualIssues == null ? List.of() : factualIssues;
    citedSourceIds = citedSourceIds == null ? List.of() : citedSourceIds;
  }

  public record MissingPointData(String keyPoint, String why) { }
}
