package interview.pilot.resume.api;

import java.time.Instant;
import java.util.UUID;

import interview.pilot.resume.domain.ResumeStatus;

public record ResumeResponse(
    Long id,
    String originalFilename,
    ResumeStatus status,
    boolean duplicate,
    UUID analysisTaskId,
    Instant createdAt,
    Object profile,
    Object evaluation,
    String analysisError) {}
