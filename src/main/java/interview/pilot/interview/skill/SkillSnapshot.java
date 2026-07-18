package interview.pilot.interview.skill;

import java.util.List;

public record SkillSnapshot(
    String id,
    String name,
    String description,
    SkillGroup group,
    List<String> defaultCompetencies,
    String persona,
    String rubric,
    List<String> references,
    String version) {

  public SkillSnapshot {
    defaultCompetencies = List.copyOf(defaultCompetencies);
    references = List.copyOf(references);
  }
}
