package interview.pilot.interview.domain;

import java.util.Objects;

import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;

public record QuestionContext(
    InterviewPlan plan,
    ResumeProfile resume,
    JobRequirements job,
    Difficulty difficulty,
    String previousQuestion,
    String previousAnswer,
    SkillSnapshot skill) {

  public QuestionContext(
      InterviewPlan plan, ResumeProfile resume, JobRequirements job, Difficulty difficulty,
      String previousQuestion, String previousAnswer) {
    this(plan, resume, job, difficulty, previousQuestion, previousAnswer, null);
  }

  public QuestionContext {
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(resume, "resume must not be null");
    Objects.requireNonNull(job, "job must not be null");
    Objects.requireNonNull(difficulty, "difficulty must not be null");
    previousQuestion = normalize(previousQuestion, "previousQuestion");
    previousAnswer = normalize(previousAnswer, "previousAnswer");
  }

  private static String normalize(String value, String name) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > 10_000) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return normalized;
  }
}
