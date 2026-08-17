package interview.pilot.interview.application;

import java.util.List;
import java.util.Objects;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.InterviewDecision;

public record AnswerEvaluationOutput(
    Double score, String feedback, List<String> evidence, List<String> missingPoints,
    List<String> redFlags, InterviewDecision suggestedDecision,
    List<AnswerEvaluation.ReferenceFact> referenceFacts,
    List<AnswerEvaluation.ReferenceFact> conflictFacts) {

  public AnswerEvaluationOutput {
    Objects.requireNonNull(score, "score is required");
    if (feedback == null || feedback.isBlank()) {
      throw new IllegalArgumentException("feedback is required");
    }
    Objects.requireNonNull(evidence, "evidence is required");
    Objects.requireNonNull(missingPoints, "missingPoints is required");
    Objects.requireNonNull(redFlags, "redFlags is required");
    Objects.requireNonNull(suggestedDecision, "suggestedDecision is required");
    Objects.requireNonNull(referenceFacts, "referenceFacts is required");
    Objects.requireNonNull(conflictFacts, "conflictFacts is required");
    new AnswerEvaluation(
        score, feedback, evidence, missingPoints, redFlags, suggestedDecision,
        referenceFacts, conflictFacts);
  }

  public AnswerEvaluation toDomain() {
    return new AnswerEvaluation(
        score, feedback, evidence, missingPoints, redFlags, suggestedDecision,
        referenceFacts, conflictFacts);
  }
}
