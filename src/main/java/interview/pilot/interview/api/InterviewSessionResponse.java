package interview.pilot.interview.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;

public record InterviewSessionResponse(
    UUID sessionId,
    Long resumeId,
    String jobTitle,
    String jdText,
    SessionStatus status,
    Difficulty difficulty,
    int currentTurnNo,
    int totalTurnBudget,
    String providerId,
    String modelName,
    InterviewPlan plan,
    List<TurnResponse> turns,
    String skillId,
    String skillName,
    String skillVersion,
    List<KnowledgeBaseSummary> knowledgeBases) {

  public InterviewSessionResponse(
      UUID sessionId, Long resumeId, String jobTitle, String jdText,
      SessionStatus status, Difficulty difficulty, int currentTurnNo, int totalTurnBudget,
      String providerId, String modelName, InterviewPlan plan, List<TurnResponse> turns) {
    this(sessionId, resumeId, jobTitle, jdText, status, difficulty, currentTurnNo,
        totalTurnBudget, providerId, modelName, plan, turns, "custom", "自定义岗位", "legacy",
        List.of());
  }

  public InterviewSessionResponse {
    turns = List.copyOf(turns);
    knowledgeBases = knowledgeBases == null ? List.of() : List.copyOf(knowledgeBases);
  }

  public record KnowledgeBaseSummary(UUID knowledgeBaseId, String name) {}

  public record TurnResponse(
      UUID requestId,
      int turnNo,
      TurnStatus status,
      Difficulty difficulty,
      String question,
      String targetCompetency,
      Instant askedAt,
      String answer,
      String feedback,
      Double score,
      List<String> evidence,
      List<String> missingPoints,
      InterviewDecision decision,
      Difficulty nextDifficulty,
      Instant answeredAt,
      String processingError,
      List<String> redFlags) {

    public TurnResponse {
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
      missingPoints = missingPoints == null ? List.of() : List.copyOf(missingPoints);
      redFlags = redFlags == null ? List.of() : List.copyOf(redFlags);
    }

    public TurnResponse(
        UUID requestId, int turnNo, TurnStatus status, Difficulty difficulty,
        String question, String targetCompetency, Instant askedAt, String answer,
        String feedback, Double score, List<String> evidence, List<String> missingPoints,
        InterviewDecision decision, Difficulty nextDifficulty, Instant answeredAt,
        String processingError) {
      this(requestId, turnNo, status, difficulty, question, targetCompetency, askedAt,
          answer, feedback, score, evidence, missingPoints, decision, nextDifficulty,
          answeredAt, processingError, List.of());
    }

    public TurnResponse(
        UUID requestId, int turnNo, TurnStatus status, Difficulty difficulty,
        String question, String targetCompetency, Instant askedAt) {
      this(requestId, turnNo, status, difficulty, question, targetCompetency, askedAt,
          null, null, null, List.of(), List.of(), null, null, null, null, List.of());
    }
  }
}
