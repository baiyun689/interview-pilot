package interview.pilot.interview.api;

import java.util.List;
import java.util.UUID;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.SessionStatus;

public record InterviewSessionResponse(
    UUID sessionId,
    Long resumeId,
    String jobTitle,
    String jdText,
    SessionStatus status,
    Difficulty difficulty,
    InterviewSize interviewSize,
    JobSourceType jobSourceType,
    int currentTurnNo,
    int totalTurnBudget,
    String providerId,
    String modelName,
    UUID preparationTaskId,
    String safeError,
    List<InterviewTurnView> turns) {
  public InterviewSessionResponse {
    turns = List.copyOf(turns);
  }
}
