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
    String version,
    Integer schemaVersion,
    List<SkillStageSpec> stages,
    List<CompetencySpec> competencySpecs,
    SkillRetrievalPolicy retrievalPolicy) {

  public SkillSnapshot {
    defaultCompetencies = defaultCompetencies == null ? List.of() : List.copyOf(defaultCompetencies);
    references = references == null ? List.of() : List.copyOf(references);
    if (schemaVersion == null || schemaVersion <= 0) schemaVersion = 1;
    stages = stages == null || stages.isEmpty()
        ? List.of(new SkillStageSpec("technical_depth", "验证核心技术能力"))
        : List.copyOf(stages);
    competencySpecs = competencySpecs == null || competencySpecs.isEmpty()
        ? legacyCompetencies(defaultCompetencies)
        : List.copyOf(competencySpecs);
    retrievalPolicy = retrievalPolicy == null
        ? SkillRetrievalPolicy.disabled() : retrievalPolicy;
  }

  public SkillSnapshot(
      String id, String name, String description, SkillGroup group,
      List<String> defaultCompetencies, String persona, String rubric,
      List<String> references, String version) {
    this(id, name, description, group, defaultCompetencies, persona, rubric, references, version,
        1, null, null, SkillRetrievalPolicy.disabled());
  }

  private static List<CompetencySpec> legacyCompetencies(List<String> names) {
    java.util.ArrayList<CompetencySpec> result = new java.util.ArrayList<>();
    for (int index = 0; index < names.size(); index++) {
      result.add(CompetencySpec.legacy("legacy-" + (index + 1), names.get(index)));
    }
    return List.copyOf(result);
  }
}
