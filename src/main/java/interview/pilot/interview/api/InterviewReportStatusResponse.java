package interview.pilot.interview.api;

import java.util.UUID;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.interview.domain.SessionStatus;

public record InterviewReportStatusResponse(
    UUID sessionId,
    SessionStatus sessionStatus,
    UUID taskId,
    AsyncTaskStatus taskStatus,
    String error,
    boolean retryable) {}
