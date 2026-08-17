package interview.pilot.interview.skill;

import java.util.List;

public record CompetencySpec(
    String id,
    String name,
    String objective,
    List<String> requiredEvidence,
    List<InterviewQuestionMode> questionModes,
    List<String> followUpAxes,
    List<String> redFlags,
    int followUpLimit,
    SkillRetrievalPolicy retrievalPolicy,
    String stageId) {

  public CompetencySpec {
    id = required(id, "competency id", 64);
    name = required(name, "competency name", 100);
    objective = optional(objective, "objective", 1_000);
    requiredEvidence = immutable(requiredEvidence);
    questionModes = questionModes == null || questionModes.isEmpty()
        ? List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.MECHANISM)
        : List.copyOf(questionModes);
    followUpAxes = immutable(followUpAxes);
    redFlags = immutable(redFlags);
    if (followUpLimit < 0 || followUpLimit > 5) {
      throw new IllegalArgumentException("followUpLimit must be between 0 and 5");
    }
    retrievalPolicy = retrievalPolicy == null
        ? SkillRetrievalPolicy.disabled() : retrievalPolicy;
    stageId = optional(stageId, "stageId", 64);
  }

  public CompetencySpec(
      String id, String name, String objective, List<String> requiredEvidence,
      List<InterviewQuestionMode> questionModes, List<String> followUpAxes,
      List<String> redFlags, int followUpLimit, SkillRetrievalPolicy retrievalPolicy) {
    this(id, name, objective, requiredEvidence, questionModes, followUpAxes,
        redFlags, followUpLimit, retrievalPolicy, "");
  }

  public static CompetencySpec legacy(String id, String name) {
    return new CompetencySpec(
        id, name, "验证" + name + "相关的真实能力证据",
        List.of("实际经验", "机制理解", "边界与取舍"),
        List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.MECHANISM,
            InterviewQuestionMode.FAILURE),
        List.of("experience", "mechanism", "failure", "tradeoff"),
        List.of(), 2, SkillRetrievalPolicy.disabled(), "");
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }

  private static String optional(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() > max) throw new IllegalArgumentException(field + " is too long");
    return normalized;
  }
}
