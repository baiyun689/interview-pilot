package interview.pilot.interview.application;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.QuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.interview.strategy.TurnDirective;

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
    QuestionDeck questionDeck,
    GeneratedQuestion firstQuestion,
    ValidatedKnowledgeScope knowledgeScope,
    RagContextSnapshot firstRagSnapshot,
    TurnDirective firstDirective) {

  public InterviewCreation(
      Long userAccountId, Long resumeId, String jobTitle, String jdText,
      Difficulty difficulty, int totalTurnBudget, String providerId, String modelName,
      SkillSnapshot skillSnapshot, JobRequirements requirements, InterviewPlan plan,
      GeneratedQuestion firstQuestion) {
    this(userAccountId, resumeId, jobTitle, jdText, difficulty, totalTurnBudget,
        providerId, modelName, skillSnapshot, requirements, plan, QuestionDeck.of(firstQuestion), firstQuestion,
        null, RagContextSnapshot.notConfigured(), null);
  }

  /** Compatibility constructor for callers that already provide grounding metadata. */
  public InterviewCreation(
      Long userAccountId, Long resumeId, String jobTitle, String jdText,
      Difficulty difficulty, int totalTurnBudget, String providerId, String modelName,
      SkillSnapshot skillSnapshot, JobRequirements requirements, InterviewPlan plan,
      GeneratedQuestion firstQuestion, ValidatedKnowledgeScope knowledgeScope,
      RagContextSnapshot firstRagSnapshot, TurnDirective firstDirective) {
    this(userAccountId, resumeId, jobTitle, jdText, difficulty, totalTurnBudget,
        providerId, modelName, skillSnapshot, requirements, plan,
        QuestionDeck.of(firstQuestion == null
            ? new GeneratedQuestion("兼容测试题", "兼容") : firstQuestion), firstQuestion, knowledgeScope,
        firstRagSnapshot, firstDirective);
  }
}
