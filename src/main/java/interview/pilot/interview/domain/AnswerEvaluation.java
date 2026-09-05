package interview.pilot.interview.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured judgment of one answered turn, produced asynchronously from the card's frozen
 * rubric (and its question-scoped reference when available) and persisted on
 * {@code interview_turn.answer_evaluation}.
 *
 * <p>The final report aggregates these per-turn judgments instead of re-scoring every raw
 * question/answer in one long prompt. {@code status} is only ever {@link EvalStatus#OK} or
 * {@link EvalStatus#GENERAL_FALLBACK} here — the remaining {@link EvalStatus} values describe
 * turns that carry no evaluation (pending / failed / skipped / not required).
 */
public record AnswerEvaluation(
    int score,
    List<String> coveredPoints,
    List<MissingPoint> missingPoints,
    List<String> factualIssues,
    List<String> citedSourceIds,
    GroundingMode groundingMode,
    EvalStatus status,
    String model,
    Instant evaluatedAt) {

  private static final int MAX_COVERED = 8;
  private static final int MAX_MISSING = 6;
  private static final int MAX_FACTUAL = 6;
  private static final int MAX_CITED = 6;

  public AnswerEvaluation {
    if (score < 0 || score > 100) {
      throw new IllegalArgumentException("evaluation score must be within 0..100");
    }
    if (status != EvalStatus.OK && status != EvalStatus.GENERAL_FALLBACK) {
      throw new IllegalArgumentException("a stored evaluation must be OK or GENERAL_FALLBACK");
    }
    if (groundingMode == null) {
      throw new IllegalArgumentException("groundingMode is required");
    }
    if (evaluatedAt == null) {
      throw new IllegalArgumentException("evaluatedAt is required");
    }
    coveredPoints = normalize(coveredPoints, 80, MAX_COVERED);
    factualIssues = normalize(factualIssues, 300, MAX_FACTUAL);
    citedSourceIds = normalize(citedSourceIds, 200, MAX_CITED);
    missingPoints = normalizeMissing(missingPoints);
    model = model == null ? "" : model.trim();
    if (groundingMode == GroundingMode.GENERAL && !citedSourceIds.isEmpty()) {
      throw new IllegalArgumentException("a GENERAL evaluation must not cite sources");
    }
  }

  private static List<String> normalize(List<String> values, int maxLength, int cap) {
    List<String> result = new ArrayList<>();
    if (values != null) {
      for (String value : values) {
        if (value == null) {
          continue;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maxLength || result.contains(trimmed)) {
          continue;
        }
        result.add(trimmed);
        if (result.size() >= cap) {
          break;
        }
      }
    }
    return List.copyOf(result);
  }

  private static List<MissingPoint> normalizeMissing(List<MissingPoint> values) {
    List<MissingPoint> result = new ArrayList<>();
    if (values != null) {
      for (MissingPoint value : values) {
        if (value == null || value.keyPoint() == null || value.keyPoint().isBlank()) {
          continue;
        }
        String keyPoint = value.keyPoint().trim();
        String why = value.why() == null ? "" : value.why().trim();
        if (keyPoint.length() > 80 || why.length() > 300) {
          continue;
        }
        result.add(new MissingPoint(keyPoint, why));
        if (result.size() >= MAX_MISSING) {
          break;
        }
      }
    }
    return List.copyOf(result);
  }

  public record MissingPoint(String keyPoint, String why) {
    public MissingPoint {
      keyPoint = keyPoint == null ? "" : keyPoint.trim();
      why = why == null ? "" : why.trim();
    }
  }
}
