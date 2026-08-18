package interview.pilot.interview.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单个能力在本场面试中的不可变证据进度快照。
 * missingEvidence、coveredTopics 和 status 由必收证据目标与已观察证据派生，调用方无法伪造。
 */
public record CompetencyProgress(
    String competencyId,
    String competency,
    String stageId,
    List<String> requiredEvidence,
    List<ObservedEvidence> observedEvidence,
    List<String> missingEvidence,
    List<String> redFlags,
    int followUpCount,
    int followUpLimit,
    int turnCount,
    boolean legacyCoverage,
    List<String> coveredTopics,
    CompetencyStatus status) {

  public CompetencyProgress {
    competencyId = required(competencyId, "competencyId", 64);
    competency = required(competency, "competency", 100);
    stageId = stageId == null ? "" : stageId.trim();
    requiredEvidence = List.copyOf(requiredEvidence);
    observedEvidence = List.copyOf(observedEvidence);
    missingEvidence = List.copyOf(missingEvidence);
    redFlags = List.copyOf(redFlags);
    if (followUpCount < 0) throw new IllegalArgumentException("followUpCount must not be negative");
    if (followUpLimit < 0 || followUpLimit > 5) {
      throw new IllegalArgumentException("followUpLimit must be between 0 and 5");
    }
    if (turnCount < 0) throw new IllegalArgumentException("turnCount must not be negative");
    coveredTopics = List.copyOf(coveredTopics);
    status = Objects.requireNonNull(status, "status must not be null");
  }

  /** 由计划项与证据事实构造快照；缺口、已覆盖主题和状态在此派生。 */
  public static CompetencyProgress create(
      String competencyId, String competency, String stageId,
      List<String> requiredEvidence, List<ObservedEvidence> observedEvidence,
      List<String> redFlags, int followUpCount, int followUpLimit, int turnCount,
      boolean legacyCoverage) {
    List<String> normalizedRedFlags = redFlags == null ? List.of() : redFlags;
    List<String> missing = new ArrayList<>();
    List<String> topics = new ArrayList<>();
    for (String target : requiredEvidence) {
      boolean observed = observedEvidence.stream()
          .anyMatch(evidence -> evidence.evidenceId().equals(target));
      if (observed) {
        if (!topics.contains(target)) topics.add(target);
      } else {
        missing.add(target);
      }
    }
    // followUpCount 是连续轮数：首轮不是追问，追问次数 = 连续轮数 - 1。
    CompetencyStatus status = legacyCoverage || missing.isEmpty()
        ? CompetencyStatus.SUFFICIENT
        : (followUpCount > followUpLimit ? CompetencyStatus.EXHAUSTED : CompetencyStatus.OPEN);
    return new CompetencyProgress(
        competencyId, competency, stageId, requiredEvidence, observedEvidence,
        missing, normalizedRedFlags, followUpCount, followUpLimit, turnCount,
        legacyCoverage, topics, status);
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
