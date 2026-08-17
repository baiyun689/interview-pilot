package interview.pilot.interview.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record InterviewPlan(
    List<String> competencies,
    int totalTurnBudget,
    Integer schemaVersion,
    List<InterviewPlanItem> items,
    List<String> omittedCompetencies,
    List<PlanOmission> omissions) {

  public InterviewPlan {
    Objects.requireNonNull(competencies, "competencies must not be null");
    List<String> normalized = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String competency : competencies) {
      String candidate = competency == null ? "" : competency.trim();
      if (!candidate.isEmpty() && seen.add(candidate.toLowerCase(Locale.ROOT))) {
        normalized.add(candidate);
      }
    }
    competencies = List.copyOf(normalized);
    if (competencies.isEmpty() || competencies.size() > 32) {
      throw new IllegalArgumentException("competencies must not be empty");
    }
    if (competencies.stream().anyMatch(value -> value.length() > 100)) {
      throw new IllegalArgumentException("competencies entries must not exceed 100 characters");
    }
    if (totalTurnBudget < 5 || totalTurnBudget > 15) {
      throw new IllegalArgumentException("totalTurnBudget must be between 5 and 15");
    }
    if (schemaVersion == null || schemaVersion <= 0) schemaVersion = 1;
    items = items == null || items.isEmpty()
        ? legacyItems(competencies, totalTurnBudget)
        : List.copyOf(items);
    if (items.stream().mapToInt(InterviewPlanItem::turnBudget).sum() != totalTurnBudget) {
      throw new IllegalArgumentException("plan item budgets must equal totalTurnBudget");
    }
    if (schemaVersion >= 2 && items.stream().anyMatch(item -> item.turnBudget() == 0)) {
      throw new IllegalArgumentException("execution plan items must have a positive turn budget");
    }
    for (String competency : competencies) {
      if (items.stream().noneMatch(item -> same(item.competency(), competency))) {
        throw new IllegalArgumentException("every competency must have a plan item");
      }
    }
    omittedCompetencies = omittedCompetencies == null ? List.of()
        : omittedCompetencies.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    omissions = omissions == null || omissions.isEmpty()
        ? omittedCompetencies.stream()
            .map(value -> new PlanOmission(value, "旧计划未记录舍弃理由"))
            .toList()
        : List.copyOf(omissions);
    if (omittedCompetencies.isEmpty() && !omissions.isEmpty()) {
      omittedCompetencies = omissions.stream().map(PlanOmission::competency).toList();
    }
  }

  public InterviewPlan(List<String> competencies, int totalTurnBudget) {
    this(competencies, totalTurnBudget, 1, null, List.of(), List.of());
  }

  public static InterviewPlan execution(
      List<InterviewPlanItem> items, int totalTurnBudget, List<PlanOmission> omissions) {
    return new InterviewPlan(
        items.stream().map(InterviewPlanItem::competency).toList(),
        totalTurnBudget, 2, items, List.of(), omissions);
  }

  public InterviewPlanItem itemFor(String competency) {
    return items.stream().filter(item -> same(item.competency(), competency)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("competency is not in the plan"));
  }

  private static List<InterviewPlanItem> legacyItems(
      List<String> competencies, int totalTurnBudget) {
    List<InterviewPlanItem> result = new ArrayList<>();
    int base = totalTurnBudget / competencies.size();
    int remainder = totalTurnBudget % competencies.size();
    for (int index = 0; index < competencies.size(); index++) {
      result.add(InterviewPlanItem.legacy(
          competencies.get(index), base + (index < remainder ? 1 : 0), index));
    }
    return List.copyOf(result);
  }

  private static boolean same(String left, String right) {
    return left.trim().equalsIgnoreCase(right.trim());
  }
}
