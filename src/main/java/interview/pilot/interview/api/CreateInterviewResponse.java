package interview.pilot.interview.api;

import java.util.UUID;

import interview.pilot.interview.domain.SessionStatus;

public record CreateInterviewResponse(
    UUID sessionId,
    SessionStatus status,
    UUID preparationTaskId) { }
