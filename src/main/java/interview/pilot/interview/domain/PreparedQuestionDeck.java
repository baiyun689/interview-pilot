package interview.pilot.interview.domain;

import java.util.List;

/**
 * Final, validated question deck produced by the two-stage preparation pipeline. Each question
 * carries its canonical knowledge point, the retrieval keywords used for its question-scoped
 * search, and the frozen rubric (evaluation standard) alongside its grounding/evidence.
 */
public record PreparedQuestionDeck(List<PreparedQuestion> questions) {

  public PreparedQuestionDeck {
    if (questions == null || questions.isEmpty()) {
      throw new IllegalArgumentException("questions must not be empty");
    }
    questions = List.copyOf(questions);
  }

  public record PreparedQuestion(
      InterviewPhase phase,
      int sequence,
      String topic,
      String question,
      List<String> focusPoints,
      String knowledgePoint,
      List<String> retrievalKeywords,
      GroundingMode groundingMode,
      List<String> evidenceRefs,
      List<RubricPoint> rubric,
      String fallbackFollowUp) {

    public PreparedQuestion {
      if (phase == null) {
        throw new IllegalArgumentException("phase is required");
      }
      if (sequence < 1) {
        throw new IllegalArgumentException("sequence must start at 1");
      }
      focusPoints = copy(focusPoints);
      retrievalKeywords = copy(retrievalKeywords);
      evidenceRefs = copy(evidenceRefs);
      rubric = rubric == null ? List.of() : List.copyOf(rubric);
      if (knowledgePoint == null || knowledgePoint.isBlank()
          || knowledgePoint.length() > 80) {
        throw new IllegalArgumentException("knowledgePoint is required and at most 80 chars");
      }
      if (retrievalKeywords.isEmpty()) {
        throw new IllegalArgumentException("retrievalKeywords must not be empty");
      }
      if (rubric.size() < 2 || rubric.size() > 4) {
        throw new IllegalArgumentException("rubric must contain 2 to 4 points");
      }
      if (groundingMode == null) {
        throw new IllegalArgumentException("groundingMode is required");
      }
      if (groundingMode == GroundingMode.GENERAL && !evidenceRefs.isEmpty()) {
        throw new IllegalArgumentException("GENERAL questions must not carry evidence refs");
      }
      if (groundingMode == GroundingMode.KNOWLEDGE_ASSISTED) {
        if (evidenceRefs.isEmpty()) {
          throw new IllegalArgumentException("KNOWLEDGE_ASSISTED questions require evidence refs");
        }
        boolean grounded = rubric.stream().anyMatch(RubricPoint::grounded);
        if (!grounded) {
          throw new IllegalArgumentException(
              "KNOWLEDGE_ASSISTED questions require at least one traceable rubric point");
        }
      }
    }

    private static List<String> copy(List<String> values) {
      return values == null ? List.of() : List.copyOf(values);
    }
  }
}
