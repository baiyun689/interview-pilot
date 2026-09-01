package interview.pilot.interview.api;

import java.time.Instant;
import java.util.UUID;

import interview.pilot.interview.domain.FixedInterviewReport;

public record FixedInterviewReportResponse(
    UUID sessionId,
    UUID reportId,
    FixedInterviewReport report,
    Instant createdAt) { }
