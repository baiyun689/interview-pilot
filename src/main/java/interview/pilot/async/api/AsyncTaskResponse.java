package interview.pilot.async.api;

import java.time.Instant;
import java.util.UUID;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;

public record AsyncTaskResponse(
    UUID taskId,
    AsyncTaskType taskType,
    AsyncTaskStatus status,
    int attemptCount,
    int publishAttempts,
    String error,
    Instant createdAt,
    Instant updatedAt) {}
