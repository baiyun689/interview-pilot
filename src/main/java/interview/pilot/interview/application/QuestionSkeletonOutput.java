package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.InterviewPhase;

/**
 * Raw LLM output of stage 1 (question skeleton). A skeleton deliberately carries no grounding /
 * evidence: question-specific RAG retrieval happens only after the questions exist.
 */
public record QuestionSkeletonOutput(int schemaVersion, List<Skeleton> questions) {

  public QuestionSkeletonOutput {
    questions = questions == null ? List.of() : List.copyOf(questions);
  }

  public record Skeleton(
      InterviewPhase phase,
      int sequence,
      String topic,
      String question,
      List<String> focusPoints,
      String knowledgePoint,
      List<String> retrievalKeywords,
      String fallbackFollowUp) {

    public Skeleton {
      focusPoints = focusPoints == null ? List.of() : List.copyOf(focusPoints);
      retrievalKeywords = retrievalKeywords == null ? List.of() : List.copyOf(retrievalKeywords);
    }
  }
}
