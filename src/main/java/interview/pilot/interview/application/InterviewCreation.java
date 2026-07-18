package interview.pilot.interview.application;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.skill.SkillSnapshot;

public record InterviewCreation(
    Long userAccountId,
    Long resumeId,
    String jobTitle,
    String jdText,
    Difficulty difficulty,
    int totalTurnBudget,
    String providerId,
    String modelName,
    SkillSnapshot skillSnapshot,
    JobRequirements requirements,
    InterviewPlan plan,
    GeneratedQuestion firstQuestion) {}
