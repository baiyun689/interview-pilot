package interview.pilot.interview.application;

import java.util.List;
import java.util.Objects;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.skill.SkillSnapshot;

public record AnswerEvaluationRequest(
    String providerId,
    String modelName,
    int turnNo,
    Difficulty difficulty,
    String competency,
    String question,
    String answer,
    List<String> requiredCompetencies,
    List<String> allowedCompetencies,
    List<String> priorEvidence,
    SkillSnapshot skill,
    RagContextSnapshot ragContext) {

  public AnswerEvaluationRequest(
      String providerId, String modelName, int turnNo, Difficulty difficulty,
      String competency, String question, String answer, List<String> requiredCompetencies,
      List<String> allowedCompetencies, List<String> priorEvidence) {
    this(providerId, modelName, turnNo, difficulty, competency, question, answer,
        requiredCompetencies, allowedCompetencies, priorEvidence, null,
        RagContextSnapshot.notConfigured());
  }

  public AnswerEvaluationRequest {
    providerId = required(providerId, "providerId", 64);
    modelName = required(modelName, "modelName", 128);
    if (turnNo < 1) throw new IllegalArgumentException("turnNo must be positive");
    difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
    competency = required(competency, "competency", 100);
    question = required(question, "question", 2_000);
    answer = required(answer, "answer", 20_000);
    requiredCompetencies = List.copyOf(requiredCompetencies);
    allowedCompetencies = List.copyOf(allowedCompetencies);
    priorEvidence = List.copyOf(priorEvidence);
  }

  private static String required(String value, String name, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}
