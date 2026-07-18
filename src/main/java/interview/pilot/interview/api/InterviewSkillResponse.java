package interview.pilot.interview.api;

import java.util.List;

import interview.pilot.interview.skill.InterviewSkill;
import interview.pilot.interview.skill.SkillGroup;

public record InterviewSkillResponse(
    String id,
    String displayName,
    String description,
    SkillGroup group,
    String icon,
    List<String> defaultCompetencies,
    String version) {
  static InterviewSkillResponse from(InterviewSkill skill) {
    return new InterviewSkillResponse(
        skill.id(), skill.name(), skill.description(), skill.group(),
        skill.display().icon(), skill.defaultCompetencies(), skill.version());
  }
}
