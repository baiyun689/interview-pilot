package interview.pilot.interview.api;

import java.time.Instant;
import java.util.UUID;

import interview.pilot.interview.domain.InterviewReport;

public record InterviewReportResponse(
    UUID sessionId, UUID reportId, InterviewReport report, Instant createdAt) {}
