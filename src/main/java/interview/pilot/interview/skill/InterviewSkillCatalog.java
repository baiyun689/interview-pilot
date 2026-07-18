package interview.pilot.interview.skill;

import java.util.List;

public interface InterviewSkillCatalog {
  List<InterviewSkill> list();

  InterviewSkill require(String skillId);
}
