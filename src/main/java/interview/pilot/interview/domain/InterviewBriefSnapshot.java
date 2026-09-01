package interview.pilot.interview.domain;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;
import java.util.Objects;

public record InterviewBriefSnapshot(
    JobSourceType jobSourceType,
    String presetId,
    String presetVersion,
    String jobTitle,
    String jobDescription,
    Long resumeId,
    ResumeProfile resume,
    Difficulty difficulty,
    InterviewSize interviewSize,
    String providerId,
    String modelName,
    ValidatedKnowledgeScope knowledgeScope,
    int flowVersion) {

  public InterviewBriefSnapshot {
    Objects.requireNonNull(jobSourceType, "jobSourceType is required");
    Objects.requireNonNull(difficulty, "difficulty is required");
    Objects.requireNonNull(interviewSize, "interviewSize is required");
    presetId = optional(presetId, 64, "presetId");
    presetVersion = optional(presetVersion, 64, "presetVersion");
    jobTitle = required(jobTitle, 200, "jobTitle");
    jobDescription = required(jobDescription, 20_000, "jobDescription");
    providerId = required(providerId, 64, "providerId");
    modelName = required(modelName, 128, "modelName");
    resume = resume == null ? ResumeProfile.empty() : resume;
    if (jobSourceType == JobSourceType.PRESET
        && (presetId.isEmpty() || presetVersion.isEmpty())) {
      throw new IllegalArgumentException("presetId and presetVersion are required for presets");
    }
    if (flowVersion < 1) throw new IllegalArgumentException("flowVersion must be positive");
  }

  public int totalMainQuestionCount() {
    return interviewSize.totalMainQuestionCount();
  }

  private static String required(String value, int max, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(name + " must be non-blank and at most " + max);
    }
    return normalized;
  }

  private static String optional(String value, int max, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() > max) {
      throw new IllegalArgumentException(name + " must be at most " + max);
    }
    return normalized;
  }
}
