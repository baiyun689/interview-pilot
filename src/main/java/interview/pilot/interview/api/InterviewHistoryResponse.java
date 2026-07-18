package interview.pilot.interview.api;

import java.time.Instant;
import java.util.UUID;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.SessionStatus;

public record InterviewHistoryResponse(
    UUID sessionId,
    String jobTitle,
    SessionStatus status,
    Difficulty difficulty,
    int currentTurnNo,
    int totalTurnBudget,
    String providerId,
    String modelName,
    Instant createdAt,
    Instant completedAt,
    String skillId,
    String skillName) {
  public InterviewHistoryResponse(
      UUID sessionId, String jobTitle, SessionStatus status, Difficulty difficulty,
      int currentTurnNo, int totalTurnBudget, String providerId, String modelName,
      Instant createdAt, Instant completedAt) {
    this(sessionId, jobTitle, status, difficulty, currentTurnNo, totalTurnBudget,
        providerId, modelName, createdAt, completedAt, "custom", "自定义岗位");
  }
}
