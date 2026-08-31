package interview.pilot.interview.preset;

public record InterviewPreset(
    String id, String displayName, String description, String jobDescription, String version) {
  public InterviewPreset {
    id = required(id, "id");
    displayName = required(displayName, "displayName");
    description = required(description, "description");
    jobDescription = required(jobDescription, "jobDescription");
    version = required(version, "version");
  }

  private static String required(String value, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
    return normalized;
  }
}
