package interview.pilot.interview.application;

import java.util.List;

public record PlanProposal(List<Item> items) {
  public PlanProposal {
    items = items == null ? List.of() : List.copyOf(items);
    if (items.isEmpty() || items.size() > 32) {
      throw new IllegalArgumentException("proposal items must contain 1 to 32 entries");
    }
  }

  public List<String> competencies() {
    return items.stream().map(Item::competency).toList();
  }

  public Item itemFor(String competency) {
    return items.stream()
        .filter(item -> interview.pilot.interview.domain.CompetencyMatcher.related(
            item.competency(), competency))
        .findFirst().orElse(null);
  }

  public record Item(
      String competency, int priorityScore, String resumeEntryPoint, String rationale) {
    public Item {
      competency = required(competency, "competency", 100);
      if (priorityScore < 0 || priorityScore > 100) {
        throw new IllegalArgumentException("priorityScore must be between 0 and 100");
      }
      resumeEntryPoint = optional(resumeEntryPoint, 500);
      rationale = required(rationale, "rationale", 500);
    }

    private static String required(String value, String field, int max) {
      String normalized = optional(value, max);
      if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
      return normalized;
    }

    private static String optional(String value, int max) {
      String normalized = value == null ? "" : value.trim();
      if (normalized.length() > max) throw new IllegalArgumentException("proposal text is too long");
      return normalized;
    }
  }
}
