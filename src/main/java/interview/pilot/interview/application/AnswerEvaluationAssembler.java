package interview.pilot.interview.application;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.EvalStatus;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.rag.RagContextSnapshot;

/**
 * Pure-Java adjudication that turns the untrusted LLM output into a validated
 * {@link AnswerEvaluation}. Mirroring {@code RubricDeckAssembler}, the model never has the final
 * word on grounding or citations:
 *
 * <ul>
 *   <li>only a card that is KNOWLEDGE_ASSISTED with a RETRIEVED snapshot is judged {@code OK};
 *       every other card (no knowledge base / no match / unavailable / GENERAL rubric) is judged
 *       {@code GENERAL_FALLBACK} and any model-claimed citations are dropped;</li>
 *   <li>kept citations must point at a chunk in this card's own snapshot (question-scoped, never
 *       another question's evidence);</li>
 *   <li>the score is clamped to 0..100; list normalization/limits live in the domain record.</li>
 * </ul>
 */
@Component
public class AnswerEvaluationAssembler {

  public AnswerEvaluation assemble(
      AnswerEvaluationOutput output, AnswerEvaluationInput input, Instant evaluatedAt) {
    if (output == null) {
      throw new IllegalArgumentException("evaluation output is required");
    }
    if (output.score() == null) {
      throw new IllegalArgumentException("evaluation score is required");
    }
    int score = clamp(output.score());

    boolean assisted = input.knowledgeAssisted();
    GroundingMode groundingMode = assisted ? GroundingMode.KNOWLEDGE_ASSISTED : GroundingMode.GENERAL;
    EvalStatus status = assisted ? EvalStatus.OK : EvalStatus.GENERAL_FALLBACK;

    List<AnswerEvaluation.MissingPoint> missing = output.missingPoints().stream()
        .filter(java.util.Objects::nonNull)
        .map(data -> new AnswerEvaluation.MissingPoint(data.keyPoint(), data.why()))
        .toList();
    List<String> cited = assisted
        ? legalCitations(output.citedSourceIds(), input.snapshot())
        : List.of();

    return new AnswerEvaluation(
        score,
        output.coveredPoints(),
        missing,
        output.factualIssues(),
        cited,
        groundingMode,
        status,
        input.modelName(),
        evaluatedAt);
  }

  private int clamp(int raw) {
    return Math.max(0, Math.min(100, raw));
  }

  private List<String> legalCitations(List<String> claimed, RagContextSnapshot snapshot) {
    // A mutable HashSet deliberately: immutable sets reject contains(null), and model output may
    // contain null/blank entries that must simply be filtered rather than crash the pipeline.
    Set<String> legal = new HashSet<>();
    snapshot.chunks().forEach(chunk -> legal.add(chunk.pointId()));
    Set<String> kept = new LinkedHashSet<>();
    for (String sourceId : claimed) {
      if (sourceId != null && !sourceId.isBlank() && legal.contains(sourceId.trim())) {
        kept.add(sourceId.trim());
      }
    }
    return List.copyOf(kept);
  }
}
