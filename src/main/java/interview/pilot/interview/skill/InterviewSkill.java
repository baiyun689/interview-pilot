package interview.pilot.interview.skill;

import java.util.List;

public record InterviewSkill(
    String id,
    String name,
    String description,
    SkillGroup group,
    Display display,
    List<String> defaultCompetencies,
    List<SkillStageSpec> stages,
    List<CompetencySpec> competencySpecs,
    SkillRetrievalPolicy retrievalPolicy,
    String persona,
    String rubric,
    List<String> redFlags,
    String version,
    int schemaVersion) {

  public InterviewSkill {
    defaultCompetencies = List.copyOf(defaultCompetencies);
    stages = List.copyOf(stages);
    competencySpecs = List.copyOf(competencySpecs);
    retrievalPolicy = retrievalPolicy == null
        ? SkillRetrievalPolicy.disabled() : retrievalPolicy;
    redFlags = redFlags == null ? List.of() : List.copyOf(redFlags);
  }

  public SkillSnapshot snapshot() {
    return new SkillSnapshot(
        id, name, description, group, defaultCompetencies,
        persona, rubric, version,
        schemaVersion, stages, competencySpecs, retrievalPolicy, redFlags);
  }

  public record Display(String icon) {}
}
