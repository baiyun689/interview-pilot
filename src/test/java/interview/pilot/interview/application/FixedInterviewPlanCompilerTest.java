package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.resume.domain.ResumeProfile;

class FixedInterviewPlanCompilerTest {
  @Test
  void createsTheFourProductPhasesInsteadOfCopyingJdCompetencies() {
    var plan = new FixedInterviewPlanCompiler().compile(
        ResumeProfile.empty(),
        new JobRequirements(List.of("完全自定义的 JD 技术点"), List.of()),
        Difficulty.HARD, 8);

    assertThat(plan.items()).extracting(item -> item.stageId()).containsExactly(
        "self_introduction", "fundamentals", "project_experience", "scenario_reflection");
    assertThat(plan.items()).extracting(item -> item.competency()).containsExactly(
        "自我介绍", "基础八股", "项目与实习", "场景题与技术感悟");
    assertThat(plan.items().getFirst().followUpLimit()).isZero();
    assertThat(plan.totalTurnBudget()).isEqualTo(8);
  }
}
