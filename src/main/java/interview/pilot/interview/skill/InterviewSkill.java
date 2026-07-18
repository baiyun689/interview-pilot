package interview.pilot.interview.skill;

import java.util.List;

public record InterviewSkill(
    String id,
    String name,
    String description,
    SkillGroup group,
    Display display,
    List<String> defaultCompetencies,
    String persona,
    String rubric,
    List<String> references,
    String version) {

  public InterviewSkill {
    defaultCompetencies = List.copyOf(defaultCompetencies);
    references = List.copyOf(references);
  }

  public SkillSnapshot snapshot() {
    return new SkillSnapshot(
        id, name, description, group, defaultCompetencies,
        persona, rubric, references, version);
  }

  public record Display(String icon) {}
}
