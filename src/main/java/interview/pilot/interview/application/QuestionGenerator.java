package interview.pilot.interview.application;

import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.QuestionContext;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;

public interface QuestionGenerator {
  GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume, JobRequirements job);

  default GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill) {
    return firstQuestion(providerId, plan, resume, job);
  }

  default GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill, RagContextSnapshot ragSnapshot) {
    return firstQuestion(providerId, plan, resume, job, skill);
  }

  GeneratedQuestion nextQuestion(
      String providerId, QuestionContext context, InterviewDecision decision);

  default GeneratedQuestion nextQuestion(
      String providerId,
      String expectedModel,
      QuestionContext context,
      InterviewDecision decision) {
    return nextQuestion(providerId, context, decision);
  }

  default GeneratedQuestion nextQuestion(
      String providerId,
      String expectedModel,
      QuestionContext context,
      InterviewDecision decision,
      RagContextSnapshot ragSnapshot) {
    return nextQuestion(providerId, expectedModel, context, decision);
  }
}
