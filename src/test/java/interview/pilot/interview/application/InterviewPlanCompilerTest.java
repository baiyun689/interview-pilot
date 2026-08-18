package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.skill.ClasspathInterviewSkillCatalog;
import interview.pilot.interview.skill.CompetencySpec;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.resume.domain.ResumeProfile;

class InterviewPlanCompilerTest {
  private final InterviewPlanCompiler compiler = new InterviewPlanCompiler();
  private final interview.pilot.interview.skill.SkillSnapshot skill =
      new ClasspathInterviewSkillCatalog().require("java-backend").snapshot();

  @Test
  void compilesDefaultsIntoABudgetedPlanDrivenByJdAndResumeEvidence() {
    InterviewPlan copiedDefaults = new InterviewPlan(specNames(skill), 8);
    ResumeProfile redisResume = profile("Redis", "Built a distributed cache", List.of("Redis"));
    JobRequirements job = new JobRequirements(List.of("MySQL"), List.of("高并发"));

    InterviewPlan plan = compiler.compile(
        copiedDefaults, redisResume, job, Difficulty.MEDIUM, 8, skill);

    assertThat(plan.schemaVersion()).isEqualTo(2);
    assertThat(plan.competencies())
        .contains("MySQL", "项目深挖", "Redis")
        .hasSizeLessThan(specNames(skill).size());
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
    InterviewPlan copiedDefaults = new InterviewPlan(specNames(skill), 6);
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
        .isEqualTo("支付项目");
    assertThat(new interview.pilot.interview.strategy.DefaultInterviewStrategy()
        .firstTurn(plan, Difficulty.MEDIUM).resumeEntryPoint())
        .isEqualTo("支付项目");
  }

  @Test
  void usesTheCompetencyStageDeclaredByTheSkillInsteadOfNameHeuristics() {
    InterviewPlan plan = compiler.compile(
        new PlanProposal(List.of(
            new PlanProposal.Item("MySQL", 100, "", "JD required"),
            new PlanProposal.Item("分布式与高可用", 90, "", "JD required"))),
        ResumeProfile.empty(),
        new JobRequirements(List.of("MySQL", "分布式与高可用"), List.of()),
        Difficulty.HARD, 5, skill);

    assertThat(plan.itemFor("MySQL").stageId()).isEqualTo("technical_depth");
    assertThat(plan.itemFor("分布式与高可用").stageId()).isEqualTo("reliability");
  }

  @Test
  void ordersSelectedCompetenciesByTheSkillStageStoryline() {
    InterviewPlan plan = compiler.compile(
        new PlanProposal(List.of(new PlanProposal.Item("MySQL", 100, "", "JD required"))),
        profile("订单项目", "负责订单数据库治理", List.of("MySQL")),
        new JobRequirements(List.of("MySQL"), List.of()),
        Difficulty.MEDIUM, 5, skill);

    assertThat(plan.items()).extracting(item -> item.stageId())
        .startsWith("project_deep_dive", "technical_depth");
  }

  @Test
  void preservesDeclaredStageOrderEvenWhenLaterStageIsRequiredByTheJob() {
    InterviewPlan plan = compiler.compile(
        new InterviewPlan(specNames(skill), 5),
        ResumeProfile.empty(),
        new JobRequirements(List.of("分布式与高可用"), List.of("MySQL")),
        Difficulty.MEDIUM, 5, skill);

    assertThat(plan.items()).extracting(item -> item.stageId())
        .containsSubsequence("technical_depth", "reliability");
    assertThat(plan.items()).noneMatch(item -> item.stageId().equals("project_deep_dive"));
  }

  @Test
  void compilesAMigratedFrontendSkillIntoAnExecutableRagPlan() {
    var frontend = new ClasspathInterviewSkillCatalog().require("frontend").snapshot();

    InterviewPlan plan = compiler.compile(
        new InterviewPlan(specNames(frontend), 5),
        ResumeProfile.empty(),
        new JobRequirements(List.of("JavaScript"), List.of("浏览器机制")),
        Difficulty.MEDIUM, 5, frontend);

    assertThat(plan.itemFor("JavaScript").stageId()).isEqualTo("technical_depth");
    assertThat(plan.itemFor("JavaScript").retrievalPolicy().scopes())
        .containsExactly("javascript", "browser");
    assertThat(plan.items()).noneMatch(item -> item.stageId().equals("project_deep_dive"));
  }

  @Test
  void discardsAResumeEntryPointThatIsNotSupportedByTheResume() {
    PlanProposal proposal = new PlanProposal(List.of(
        new PlanProposal.Item("Java 基础与并发", 95, "不存在的证券交易项目", "模型建议")));

    InterviewPlan plan = compiler.compile(
        proposal, profile("支付项目", "并发扣款", List.of("Java")),
        new JobRequirements(List.of("Java 基础与并发"), List.of()),
        Difficulty.MEDIUM, 5, skill);

    assertThat(plan.itemFor("Java 基础与并发").resumeEntryPoint()).isEmpty();
  }

  @Test
  void keepsOnlyTheImmutableResumeFactFromAPartiallyGroundedEntryPoint() {
    PlanProposal proposal = new PlanProposal(List.of(
        new PlanProposal.Item("Java 基础与并发", 95, "Java 证券交易平台", "模型建议")));

    InterviewPlan plan = compiler.compile(
        proposal, profile("支付项目", "并发扣款", List.of("Java")),
        new JobRequirements(List.of("Java 基础与并发"), List.of()),
        Difficulty.MEDIUM, 5, skill);

    assertThat(plan.itemFor("Java 基础与并发").resumeEntryPoint()).isEqualTo("Java");
  }

  private ResumeProfile profile(String name, String description, List<String> technologies) {
    return new ResumeProfile(
        description, technologies,
        List.of(new ResumeProfile.ProjectEvidence(name, description, technologies)),
        List.of(), List.of());
  }

  private static List<String> specNames(SkillSnapshot skill) {
    return skill.competencySpecs().stream().map(CompetencySpec::name).toList();
  }
}
