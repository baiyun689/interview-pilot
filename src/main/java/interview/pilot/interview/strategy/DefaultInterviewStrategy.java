package interview.pilot.interview.strategy;

import java.util.ArrayList;
import java.util.List;

import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.skill.InterviewQuestionMode;

/**
 * 证据驱动的确定性策略。决策顺序固定：
 * 1. 硬轮次上限直接结束（记录未完成证据由 FINISH 指令承担）；
 * 2. 当前能力仍有证据缺口且未达追问上限，生成定向追问；
 * 3. 当前能力已充分或已耗尽，切换到同 stage 下一个未充分能力；
 * 4. 当前 stage 完成或预算耗尽后进入下一个 stage；
 * 5. 所有能力均已充分或耗尽才允许 FINISH。
 * AI 建议只贡献难度调整与置信度，动作、目标能力与理由由本类重算。
 */
public final class DefaultInterviewStrategy implements InterviewStrategy {

  @Override
  public TurnDirective firstTurn(InterviewPlan plan, Difficulty difficulty) {
    InterviewPlanItem item = plan.items().getFirst();
    return directive(item, difficulty, 0, item.evidenceTargets().getFirst(),
        "PLAN_FIRST_TURN", java.util.List.of());
  }

  @Override
  public StrategyOutcome nextTurn(
      InterviewPlan plan, InterviewProgress progress, TurnAssessment assessment,
      DecisionContext context) {
    InterviewPlanItem currentItem = plan.itemFor(context.currentCompetency());
    InterviewDecision suggestion = trustedSuggestion(assessment.suggestedDecision(),
        context.minimumConfidence());

    InterviewProgress tentative = progress.apply(assessment, currentItem);
    CompetencyProgress current = tentative.progressOf(currentItem.competencyId());

    if (context.currentTurn() >= context.totalTurnBudget()) {
      return finish("TURN_BUDGET_EXHAUSTED", confidenceOf(suggestion), plan, tentative,
          context.currentDifficulty());
    }

    // The current product flow has four fixed sections.  In this mode a
    // follow-up is a small conversational probe, not an evaluation-driven
    // evidence loop.  The answer evaluator still produces the score/report,
    // but it cannot unexpectedly reorder the interview or add a follow-up to
    // the self-introduction.
    if (isFixedFlow(plan)) {
      if (!"self_introduction".equals(currentItem.stageId())
          && current.turnCount() <= currentItem.followUpLimit()) {
        return followUp(tentative, currentItem, current, suggestion, context);
      }
      Candidate next = nextFixedCandidate(plan, tentative, currentItem);
      if (next == null) {
        return finish("FIXED_FLOW_COMPLETED", confidenceOf(suggestion), plan, tentative,
            context.currentDifficulty());
      }
      InterviewDecision decision = new InterviewDecision(
          NextStep.NEXT_TOPIC, boundedAdjustment(suggestion, context.currentDifficulty()),
          next.item().competency(), "", "FIXED_FLOW_NEXT_SECTION", confidenceOf(suggestion));
      TurnDirective directive = directive(
          next.item(), adjust(context.currentDifficulty(), decision.difficultyAdjustment()), 0,
          firstMissing(next.progress()), "FIXED_FLOW_NEXT_SECTION", coveredTopics(tentative));
      return new StrategyOutcome(decision, directive, tentative);
    }

    if (current.status() == CompetencyStatus.OPEN) {
      return followUp(tentative, currentItem, current, suggestion, context);
    }

    Candidate next = nextCandidate(plan, tentative, currentItem);
    if (next == null) {
      return finish("ALL_COMPETENCIES_SETTLED", confidenceOf(suggestion), plan, tentative,
          context.currentDifficulty());
    }
    DifficultyAdjustment adjustment = boundedAdjustment(suggestion, context.currentDifficulty());
    InterviewDecision decision = new InterviewDecision(
        NextStep.NEXT_TOPIC, adjustment, next.item().competency(), "", next.reason(),
        confidenceOf(suggestion));
    TurnDirective directive = directive(
        next.item(), adjust(context.currentDifficulty(), adjustment), 0,
        firstMissing(next.progress()), next.reason(), coveredTopics(tentative));
    return new StrategyOutcome(decision, directive, tentative);
  }

  private StrategyOutcome followUp(
      InterviewProgress tentative, InterviewPlanItem item, CompetencyProgress current,
      InterviewDecision suggestion, DecisionContext context) {
    DifficultyAdjustment adjustment = boundedAdjustment(suggestion, context.currentDifficulty());
    String probeFocus = evidenceGap(current, item);
    InterviewDecision decision = new InterviewDecision(
        NextStep.FOLLOW_UP, adjustment, item.competency(), probeFocus,
        "EVIDENCE_GAP_FOLLOW_UP", confidenceOf(suggestion));
    TurnDirective directive = directive(
        item, adjust(context.currentDifficulty(), adjustment), current.followUpCount(),
        probeFocus, "EVIDENCE_GAP_FOLLOW_UP", coveredTopics(tentative));
    return new StrategyOutcome(decision, directive, tentative);
  }

  private StrategyOutcome finish(
      String reason, double confidence, InterviewPlan plan, InterviewProgress tentative,
      Difficulty difficulty) {
    return new StrategyOutcome(
        new InterviewDecision(NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", reason, confidence),
        TurnDirective.finish(reason, difficulty, tentative.unfinishedSummary(plan)),
        tentative);
  }

  private record Candidate(InterviewPlanItem item, CompetencyProgress progress, String reason) {}

  private boolean isFixedFlow(InterviewPlan plan) {
    return plan.items().size() == 4
        && "self_introduction".equals(plan.items().getFirst().stageId())
        && "fundamentals".equals(plan.items().get(1).stageId())
        && "project_experience".equals(plan.items().get(2).stageId())
        && "scenario_reflection".equals(plan.items().get(3).stageId());
  }

  private Candidate nextFixedCandidate(
      InterviewPlan plan, InterviewProgress progress, InterviewPlanItem currentItem) {
    int currentIndex = plan.items().indexOf(currentItem);
    for (int index = currentIndex + 1; index < plan.items().size(); index++) {
      InterviewPlanItem item = plan.items().get(index);
      CompetencyProgress candidate = progress.progressOf(item.competencyId());
      if (candidate != null && candidate.turnCount() < item.turnBudget()) {
        return new Candidate(item, candidate, "FIXED_FLOW_NEXT_SECTION");
      }
    }
    return null;
  }

  /** 按 stage 顺序寻找下一个未充分能力：先同 stage，再后续 stage，最后回查前面的 stage。 */
  private Candidate nextCandidate(
      InterviewPlan plan, InterviewProgress progress, InterviewPlanItem currentItem) {
    List<String> stageOrder = plan.items().stream()
        .map(InterviewPlanItem::stageId)
        .distinct()
        .toList();
    int currentStageIndex = stageOrder.indexOf(currentItem.stageId());

    Candidate sameStage = firstOpen(plan, progress, currentItem.stageId());
    if (sameStage != null) {
      return new Candidate(sameStage.item(), sameStage.progress(),
          "SWITCH_AFTER_EVIDENCE_SETTLED");
    }

    String exitReason = stageExitReason(currentItem.stageId(), plan, progress);
    for (int index = currentStageIndex + 1; index < stageOrder.size(); index++) {
      Candidate candidate = firstOpen(plan, progress, stageOrder.get(index));
      if (candidate != null) {
        return new Candidate(candidate.item(), candidate.progress(), exitReason);
      }
    }
    for (int index = 0; index < currentStageIndex; index++) {
      Candidate candidate = firstOpen(plan, progress, stageOrder.get(index));
      if (candidate != null) {
        return new Candidate(candidate.item(), candidate.progress(), exitReason);
      }
    }
    return null;
  }

  private Candidate firstOpen(InterviewPlan plan, InterviewProgress progress, String stageId) {
    for (InterviewPlanItem item : plan.items()) {
      if (!item.stageId().equals(stageId)) continue;
      CompetencyProgress candidate = progress.progressOf(item.competencyId());
      if (candidate != null && candidate.status() == CompetencyStatus.OPEN) {
        return new Candidate(item, candidate, "");
      }
    }
    return null;
  }

  private String stageExitReason(String stageId, InterviewPlan plan, InterviewProgress progress) {
    if (progress.requiredSufficient(stageId, plan)) return "STAGE_COMPLETED_REQUIRED_EVIDENCE";
    if (progress.stageBudgetExhausted(stageId, plan)) return "STAGE_BUDGET_EXHAUSTED";
    return "SWITCH_AFTER_LOW_VALUE_FOLLOW_UP";
  }

  private TurnDirective directive(
      InterviewPlanItem item, Difficulty difficulty, int modeIndex,
      String probeFocus, String reason, List<String> coveredTopics) {
    InterviewQuestionMode mode = item.questionModes().get(
        Math.min(modeIndex, item.questionModes().size() - 1));
    return new TurnDirective(
        item.stageId(), item.competency(), difficulty, item.evidenceTargets(),
        mode, item.ragEnabled(), probeFocus, reason, item.resumeEntryPoint(),
        item.retrievalPolicy(), coveredTopics);
  }

  private String evidenceGap(CompetencyProgress progress, InterviewPlanItem item) {
    if (progress.missingEvidence().isEmpty()) return "补充可核验的工程证据";
    String target = progress.missingEvidence().getFirst();
    return withAxis(target, item, Math.max(0, progress.followUpCount() - 1));
  }

  private String firstMissing(CompetencyProgress progress) {
    if (progress.missingEvidence().isEmpty()) return "补充可核验的工程证据";
    return progress.missingEvidence().getFirst();
  }

  private String withAxis(String target, InterviewPlanItem item, int followUpCount) {
    if (item.followUpAxes().isEmpty()) return target;
    String axis = item.followUpAxes().get(
        Math.min(followUpCount, item.followUpAxes().size() - 1));
    return target + "；追问角度：" + axis;
  }

  private List<String> coveredTopics(InterviewProgress progress) {
    List<String> topics = new ArrayList<>();
    for (CompetencyProgress candidate : progress.byCompetencyId().values()) {
      for (String topic : candidate.coveredTopics()) {
        if (!topics.contains(topic)) topics.add(topic);
      }
    }
    return List.copyOf(topics);
  }

  /** AI 建议只有合法且置信度达标时才是候选；非法建议被整体忽略。 */
  private InterviewDecision trustedSuggestion(InterviewDecision suggestion, double minimumConfidence) {
    if (suggestion == null
        || suggestion.nextStep() == null
        || suggestion.difficultyAdjustment() == null
        || !Double.isFinite(suggestion.confidence())
        || suggestion.confidence() < minimumConfidence
        || suggestion.confidence() > 1) {
      return null;
    }
    return suggestion;
  }

  private double confidenceOf(InterviewDecision suggestion) {
    return suggestion == null ? 0 : suggestion.confidence();
  }

  private DifficultyAdjustment boundedAdjustment(
      InterviewDecision suggestion, Difficulty currentDifficulty) {
    // FINISH 建议被 Java 否决继续考察时，整体建议（含难度调整）一并忽略。
    if (suggestion == null || suggestion.nextStep() == NextStep.FINISH) {
      return DifficultyAdjustment.KEEP;
    }
    DifficultyAdjustment adjustment = suggestion.difficultyAdjustment();
    if ((adjustment == DifficultyAdjustment.INCREASE && currentDifficulty == Difficulty.HARD)
        || (adjustment == DifficultyAdjustment.DECREASE && currentDifficulty == Difficulty.EASY)) {
      return DifficultyAdjustment.KEEP;
    }
    return adjustment;
  }

  private Difficulty adjust(Difficulty current, DifficultyAdjustment adjustment) {
    return switch (adjustment) {
      case KEEP -> current;
      case INCREASE -> current == Difficulty.EASY ? Difficulty.MEDIUM : Difficulty.HARD;
      case DECREASE -> current == Difficulty.HARD ? Difficulty.MEDIUM : Difficulty.EASY;
    };
  }
}
