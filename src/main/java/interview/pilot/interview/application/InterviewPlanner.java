package interview.pilot.interview.application;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;

public interface InterviewPlanner {
  InterviewPlan plan(
      String providerId,
      ResumeProfile resume,
      JobRequirements job,
      Difficulty difficulty,
      int turns);

  default InterviewPlan plan(
      String providerId, ResumeProfile resume, JobRequirements job,
      Difficulty difficulty, int turns, SkillSnapshot skill) {
    return plan(providerId, resume, job, difficulty, turns);
  }
}
