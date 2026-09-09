package interview.pilot.interview.domain;

/**
 * A single, scorable expectation for one question card (the evaluation standard).
 *
 * <p>{@code keyPoint} names the point, {@code acceptanceHint} describes what an answer must
 * mention to cover it, and {@code sourcePointId} optionally traces the point back to a chunk in
 * the card's own RAG snapshot. A GENERAL card (no knowledge grounding) always has a null
 * sourcePointId; a KNOWLEDGE_ASSISTED card must have at least one point that is traceable.
 */
public record RubricPoint(String keyPoint, String acceptanceHint, String sourcePointId) {

  public RubricPoint {
    keyPoint = required(keyPoint, 300, "keyPoint");
    acceptanceHint = required(acceptanceHint, 1000, "acceptanceHint");
    sourcePointId = (sourcePointId == null || sourcePointId.isBlank()) ? null : sourcePointId.trim();
  }

  public RubricPoint(String keyPoint, String acceptanceHint) {
    this(keyPoint, acceptanceHint, null);
  }

  public boolean grounded() {
    return sourcePointId != null;
  }

  private static String required(String value, int max, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " must be non-blank and at most " + max + " chars");
    }
    return normalized;
  }
}
