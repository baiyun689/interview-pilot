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
    List<String> references,
    String version) {

  public InterviewSkill {
    defaultCompetencies = List.copyOf(defaultCompetencies);
    stages = List.copyOf(stages);
    competencySpecs = List.copyOf(competencySpecs);
    retrievalPolicy = retrievalPolicy == null
        ? SkillRetrievalPolicy.disabled() : retrievalPolicy;
    references = List.copyOf(references);
  }

  public SkillSnapshot snapshot() {
    return new SkillSnapshot(
        id, name, description, group, defaultCompetencies,
        persona, rubric, references, version,
        2, stages, competencySpecs, retrievalPolicy);
  }

  public record Display(String icon) {}
}
