package interview.pilot.interview.application;

import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.skill.SkillSnapshot;

public interface JobProfileExtractor {
  JobRequirements extract(String providerId, String jdText);

  default JobRequirements extract(String providerId, String jdText, SkillSnapshot skill) {
    return extract(providerId, jdText);
  }
}
