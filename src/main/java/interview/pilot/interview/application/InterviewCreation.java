package interview.pilot.interview.application;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

public record InterviewCreation(
    Long userAccountId,
    Long resumeId,   // nullable – interview may be created without a resume
    String jobTitle,
    String jdText,
    Difficulty difficulty,
    int totalTurnBudget,
    String providerId,
    String modelName,
    SkillSnapshot skillSnapshot,
    JobRequirements requirements,
    InterviewPlan plan,
    GeneratedQuestion firstQuestion,
    ValidatedKnowledgeScope knowledgeScope,
    RagContextSnapshot firstRagSnapshot) {

  public InterviewCreation(
      Long userAccountId, Long resumeId, String jobTitle, String jdText,
      Difficulty difficulty, int totalTurnBudget, String providerId, String modelName,
      SkillSnapshot skillSnapshot, JobRequirements requirements, InterviewPlan plan,
      GeneratedQuestion firstQuestion) {
    this(userAccountId, resumeId, jobTitle, jdText, difficulty, totalTurnBudget,
        providerId, modelName, skillSnapshot, requirements, plan, firstQuestion,
        null, RagContextSnapshot.notConfigured());
  }
}
