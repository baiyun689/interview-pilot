package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.RubricPoint;
import interview.pilot.interview.rag.RagContextSnapshot;

/**
 * Everything the asynchronous evaluator needs to judge one answered turn. Assembled inside a
 * short transaction from the completed turn and its frozen question card; the slow LLM call that
 * consumes it runs outside any transaction.
 */
public record AnswerEvaluationInput(
    InterviewPhase phase,
    String question,
    String answer,
    Difficulty difficulty,
    List<RubricPoint> rubric,
    GroundingMode cardGroundingMode,
    RagContextSnapshot snapshot,
    String providerId,
    String modelName) {

  public AnswerEvaluationInput {
    rubric = rubric == null ? List.of() : List.copyOf(rubric);
    if (question == null || question.isBlank()) {
      throw new IllegalArgumentException("evaluation question is required");
    }
    if (answer == null || answer.isBlank()) {
      throw new IllegalArgumentException("evaluation answer is required");
    }
    if (cardGroundingMode == null || snapshot == null) {
      throw new IllegalArgumentException("card grounding and snapshot are required");
    }
  }

  /** A card may only be judged with knowledge assistance when its own snapshot retrieved. */
  public boolean knowledgeAssisted() {
    return cardGroundingMode == GroundingMode.KNOWLEDGE_ASSISTED
        && snapshot.status() == interview.pilot.interview.rag.RagStatus.RETRIEVED;
  }
}
