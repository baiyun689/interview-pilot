package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.skill.SkillSnapshot;

public interface ReportGenerator {
  InterviewReport generate(
      String providerId, String expectedModel, List<ReportEvidence> completedTurnEvidence);

  default InterviewReport generate(
      String providerId, String expectedModel, List<ReportEvidence> completedTurnEvidence,
      SkillSnapshot skill) {
    return generate(providerId, expectedModel, completedTurnEvidence);
  }
}
