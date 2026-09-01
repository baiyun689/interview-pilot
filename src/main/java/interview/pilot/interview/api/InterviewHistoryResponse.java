package interview.pilot.interview.api;

import java.time.Instant;
import java.util.UUID;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.SessionStatus;

public record InterviewHistoryResponse(
    UUID sessionId,
    String jobTitle,
    SessionStatus status,
    Difficulty difficulty,
    InterviewSize interviewSize,
    JobSourceType jobSourceType,
    int currentTurnNo,
    int currentMainQuestionNo,
    int totalMainQuestionCount,
    String providerId,
    String modelName,
    String safeError,
    Instant createdAt,
    Instant completedAt) { }
