package interview.pilot.interview.domain;

public record PlanOmission(String competency, String reason) {
  public PlanOmission {
    competency = required(competency, "competency");
    reason = required(reason, "reason");
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }
}
