package interview.pilot.interview.domain;

import java.util.Objects;

import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;

import interview.pilot.interview.rag.RagContextSnapshot;

public record QuestionContext(
    InterviewPlan plan,
    ResumeProfile resume,
    JobRequirements job,
    Difficulty difficulty,
    String previousQuestion,
    String previousAnswer,
    SkillSnapshot skill,
    RagContextSnapshot ragSnapshot) {

  public QuestionContext(
      InterviewPlan plan, ResumeProfile resume, JobRequirements job, Difficulty difficulty,
      String previousQuestion, String previousAnswer) {
    this(plan, resume, job, difficulty, previousQuestion, previousAnswer, null,
        RagContextSnapshot.notConfigured());
  }

  public QuestionContext(
      InterviewPlan plan, ResumeProfile resume, JobRequirements job, Difficulty difficulty,
      String previousQuestion, String previousAnswer, SkillSnapshot skill) {
    this(plan, resume, job, difficulty, previousQuestion, previousAnswer, skill,
        RagContextSnapshot.notConfigured());
  }

  public QuestionContext withRag(RagContextSnapshot rag) {
    return new QuestionContext(plan, resume, job, difficulty, previousQuestion, previousAnswer,
        skill, rag != null ? rag : RagContextSnapshot.notConfigured());
  }

  public QuestionContext {
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(resume, "resume must not be null");
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(difficulty, "difficulty must not be null");
    previousQuestion = normalize(previousQuestion, "previousQuestion");
    previousAnswer = normalize(previousAnswer, "previousAnswer");
    ragSnapshot = ragSnapshot == null ? RagContextSnapshot.notConfigured() : ragSnapshot;
  }

  private static String normalize(String value, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 10_000) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}
