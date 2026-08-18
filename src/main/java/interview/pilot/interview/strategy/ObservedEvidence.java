package interview.pilot.interview.strategy;

/** 一条已验证的候选人证据：对应 Skill 证据目标、带回答原文依据和观察轮次。 */
public record ObservedEvidence(String evidenceId, String claim, int turnNo) {

  public ObservedEvidence {
    evidenceId = required(evidenceId, "evidenceId", 64);
    claim = required(claim, "claim", 1_000);
    if (turnNo < 0) throw new IllegalArgumentException("turnNo must not be negative");
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
