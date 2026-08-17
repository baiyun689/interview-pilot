package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.skill.ClasspathInterviewSkillCatalog;
import interview.pilot.resume.domain.ResumeProfile;

class InterviewPlanCompilerTest {
  private final InterviewPlanCompiler compiler = new InterviewPlanCompiler();
  private final interview.pilot.interview.skill.SkillSnapshot skill =
      new ClasspathInterviewSkillCatalog().require("java-backend").snapshot();

  @Test
  void compilesDefaultsIntoABudgetedPlanDrivenByJdAndResumeEvidence() {
    InterviewPlan copiedDefaults = new InterviewPlan(skill.defaultCompetencies(), 8);
    ResumeProfile redisResume = profile("Redis", "Built a distributed cache", List.of("Redis"));
    JobRequirements job = new JobRequirements(List.of("MySQL"), List.of("高并发"));

    InterviewPlan plan = compiler.compile(
        copiedDefaults, redisResume, job, Difficulty.MEDIUM, 8, skill);

    assertThat(plan.schemaVersion()).isEqualTo(2);
    assertThat(plan.competencies())
        .contains("MySQL", "项目深挖", "Redis")
        .hasSizeLessThan(skill.defaultCompetencies().size());
    assertThat(plan.items()).allSatisfy(item -> {
      assertThat(item.evidenceTargets()).isNotEmpty();
      assertThat(item.questionModes()).isNotEmpty();
      assertThat(item.rationale()).isNotBlank();
    });
    assertThat(plan.items().stream().mapToInt(item -> item.turnBudget()).sum()).isEqualTo(8);
    assertThat(plan.omittedCompetencies()).isNotEmpty();
    assertThat(plan.omissions()).allSatisfy(omission -> assertThat(omission.reason()).isNotBlank());
  }

  @Test
  void differentResumeEvidenceChangesTheOptionalPlanSelection() {
    InterviewPlan copiedDefaults = new InterviewPlan(skill.defaultCompetencies(), 6);
    JobRequirements job = new JobRequirements(List.of("Java 基础与并发"), List.of());

    InterviewPlan redis = compiler.compile(
        copiedDefaults, profile("Cache", "Redis hotspot", List.of("Redis")),
        job, Difficulty.MEDIUM, 6, skill);
    InterviewPlan spring = compiler.compile(
        copiedDefaults, profile("Order", "Spring transaction", List.of("Spring")),
        job, Difficulty.MEDIUM, 6, skill);

    assertThat(redis.competencies()).contains("Redis");
    assertThat(spring.competencies()).contains("Spring 与事务");
    assertThat(redis.competencies()).isNotEqualTo(spring.competencies());
  }

  @Test
  void hardJdRequirementsAreNeverDroppedWhenTheBudgetIsTight() {
    JobRequirements job = new JobRequirements(
        List.of("Java 基础与并发", "Spring 与事务", "MySQL", "Redis", "安全"),
        List.of());

    InterviewPlan plan = compiler.compile(
        new InterviewPlan(job.competencies(), 5), ResumeProfile.empty(),
        job, Difficulty.HARD, 5, skill);

    assertThat(plan.competencies()).containsAll(job.competencies());
    assertThat(plan.items()).hasSize(5);
    assertThat(plan.items()).allMatch(item -> item.turnBudget() == 1);
  }

  @Test
  void keepsLegacyPlansReadableWhenTheyContainMoreCompetenciesThanTurns() {
    InterviewPlan legacy = new InterviewPlan(
        List.of("A", "B", "C", "D", "E", "F"), 5);

    assertThat(legacy.schemaVersion()).isEqualTo(1);
    assertThat(legacy.items()).hasSize(6);
    assertThat(legacy.items().stream().mapToInt(item -> item.turnBudget()).sum()).isEqualTo(5);
    assertThat(legacy.items()).anyMatch(item -> item.turnBudget() == 0);
  }

  @Test
  void carriesTheProposedResumeEntryPointIntoTheExecutionPlan() {
    PlanProposal proposal = new PlanProposal(List.of(
        new PlanProposal.Item("Java 基础与并发", 95, "支付项目的并发扣款", "简历强相关")));

    InterviewPlan plan = compiler.compile(
        proposal, profile("支付项目", "并发扣款", List.of("Java")),
        new JobRequirements(List.of("Java 基础与并发"), List.of()),
        Difficulty.MEDIUM, 5, skill);

    assertThat(plan.itemFor("Java 基础与并发").resumeEntryPoint())
        .isEqualTo("支付项目的并发扣款");
    assertThat(new interview.pilot.interview.strategy.DefaultInterviewStrategy()
        .firstTurn(plan, Difficulty.MEDIUM).resumeEntryPoint())
        .isEqualTo("支付项目的并发扣款");
  }

  private ResumeProfile profile(String name, String description, List<String> technologies) {
    return new ResumeProfile(
        description, technologies,
        List.of(new ResumeProfile.ProjectEvidence(name, description, technologies)),
        List.of(), List.of());
  }
}
