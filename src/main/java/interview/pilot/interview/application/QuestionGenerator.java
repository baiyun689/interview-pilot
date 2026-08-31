package interview.pilot.interview.application;

import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.QuestionDeck;
import interview.pilot.interview.domain.QuestionContext;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.interview.strategy.TurnDirective;

public interface QuestionGenerator {
  default QuestionDeck generatePrimaryQuestions(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill, RagContextSnapshot ragSnapshot,
      TurnDirective directive) {
    return QuestionDeck.of(firstQuestion(
        providerId, plan, resume, job, skill, ragSnapshot, directive));
  }

  default GeneratedQuestion generateFollowUpQuestion(
      String providerId, String expectedModel, QuestionContext context,
      InterviewDecision decision, TurnDirective directive) {
    return nextQuestion(providerId, expectedModel, context, decision, directive);
  }

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

  default GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill, RagContextSnapshot ragSnapshot,
      TurnDirective directive) {
    return firstQuestion(providerId, plan, resume, job, skill, ragSnapshot);
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
      TurnDirective directive) {
    return nextQuestion(providerId, expectedModel, context, decision);
  }
}
