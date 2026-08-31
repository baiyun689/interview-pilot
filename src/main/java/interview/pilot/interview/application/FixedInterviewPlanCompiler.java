package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;
import interview.pilot.resume.domain.ResumeProfile;

/**
 * The runtime interview outline. It is deliberately fixed: JD, resume and RAG
 * enrich the questions, but they do not invent or reorder the interview phases.
 */
@Component
public final class FixedInterviewPlanCompiler {
  private static final List<Phase> PHASES = List.of(
      new Phase("self_introduction", "自我介绍", List.of("经历概览", "岗位相关性"),
          List.of(InterviewQuestionMode.CONCEPT), 0),
      new Phase("fundamentals", "基础八股", List.of("核心概念", "机制理解", "边界条件"),
          List.of(InterviewQuestionMode.CONCEPT, InterviewQuestionMode.MECHANISM), 1),
      new Phase("project_experience", "项目与实习", List.of("个人贡献", "实现细节", "结果指标"),
          List.of(InterviewQuestionMode.PROJECT, InterviewQuestionMode.METRICS), 2),
      new Phase("scenario_reflection", "场景题与技术感悟", List.of("方案取舍", "失败处理", "演进思路"),
          List.of(InterviewQuestionMode.ARCHITECTURE, InterviewQuestionMode.FAILURE,
              InterviewQuestionMode.TRADEOFF), 2));

  public InterviewPlan compile(
      ResumeProfile resume, JobRequirements job, Difficulty difficulty, int totalTurnBudget) {
    int[] budgets = allocate(totalTurnBudget);
    List<InterviewPlanItem> items = new ArrayList<>();
    for (int index = 0; index < PHASES.size(); index++) {
      Phase phase = PHASES.get(index);
      items.add(new InterviewPlanItem(
          phase.id(), phase.id(), phase.name(), PlanPriority.REQUIRED, budgets[index],
          phase.evidenceTargets(), phase.modes(),
          "固定面试流程阶段；具体技术点由 JD、简历和 RAG 补充",
          index >= 2, phase.evidenceTargets(), Math.min(phase.followUpLimit(), budgets[index] - 1),
          resumeEntryPoint(phase, resume),
          index >= 2 ? new SkillRetrievalPolicy(true, List.of(),
              List.of(interview.pilot.interview.skill.GroundingUse.GENERATE_SCENARIO,
                  interview.pilot.interview.skill.GroundingUse.VERIFY_FACT))
              : SkillRetrievalPolicy.disabled()));
    }
    return InterviewPlan.execution(items, totalTurnBudget, List.of());
  }

  private int[] allocate(int totalTurnBudget) {
    if (totalTurnBudget < PHASES.size()) {
      throw new IllegalArgumentException("totalTurnBudget must cover the fixed interview phases");
    }
    int[] result = {1, 1, 1, 1};
    for (int remaining = totalTurnBudget - PHASES.size(), index = 1;
        remaining > 0; remaining--, index++) {
      result[index % result.length]++;
    }
    return result;
  }

  private String resumeEntryPoint(Phase phase, ResumeProfile resume) {
    if (resume == null || resume.projects().isEmpty() || phase != PHASES.get(2)) return "";
    return resume.projects().getFirst().name();
  }

  private record Phase(
      String id, String name, List<String> evidenceTargets,
      List<InterviewQuestionMode> modes, int followUpLimit) {}
}
