package interview.pilot.interview.api;

import java.util.UUID;

import interview.pilot.interview.domain.SessionStatus;

public record StartInterviewResponse(
    UUID sessionId,
    SessionStatus status,
    InterviewTurnView currentTurn,
    boolean idempotentReplay) { }
