package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPhase;

/**
 * Question-scoped retrieval seed: unlike the old phase-wide query, the vector search is driven by
 * exactly one question (its text + canonical knowledge point + focused keywords).
 */
public record QuestionRetrievalSeed(
    InterviewPhase phase,
    String knowledgePoint,
    List<String> keywords,
    String questionText,
    Difficulty difficulty) {

  private static final int MAX_KEYWORDS = 8;

  public QuestionRetrievalSeed {
    Objects.requireNonNull(phase, "phase is required");
    Objects.requireNonNull(difficulty, "difficulty is required");
    knowledgePoint = knowledgePoint == null ? "" : knowledgePoint.trim();
    questionText = questionText == null ? "" : questionText.trim();
    if (questionText.isEmpty()) {
      throw new IllegalArgumentException("questionText is required");
    }
    var cleaned = new ArrayList<String>();
    if (keywords != null) {
      for (String keyword : keywords) {
        if (keyword == null) {
          continue;
        }
        String trimmed = keyword.trim();
        if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
          cleaned.add(trimmed);
        }
      }
    }
    keywords = List.copyOf(cleaned.size() > MAX_KEYWORDS ? cleaned.subList(0, MAX_KEYWORDS) : cleaned);
  }

  /** Builds the actual vector query: question text first, then the canonical point and keywords. */
  public String query() {
    var parts = new ArrayList<String>();
    parts.add(questionText);
    if (!knowledgePoint.isEmpty()) {
      parts.add(knowledgePoint);
    }
    parts.addAll(keywords);
    return String.join(" ", parts).trim();
  }
}
