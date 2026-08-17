package interview.pilot.interview.strategy;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.CompetencyMatcher;
import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewDecisionPolicy;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.skill.InterviewQuestionMode;

public final class DefaultInterviewStrategy implements InterviewStrategy {
  private final InterviewDecisionPolicy decisionPolicy = new InterviewDecisionPolicy();

  @Override
  public TurnDirective firstTurn(InterviewPlan plan, Difficulty difficulty) {
    InterviewPlanItem item = plan.items().getFirst();
    return directive(item, difficulty, 0, item.evidenceTargets().getFirst(), "PLAN_FIRST_TURN");
  }

  @Override
  public StrategyOutcome nextTurn(
      InterviewPlan plan, DecisionContext context, AnswerEvaluation assessment) {
    InterviewDecision suggestion = trustedSuggestion(assessment.suggestedDecision(), plan);
    InterviewPlanItem currentItem = plan.itemFor(context.currentCompetency());
    InterviewDecision decision = decisionPolicy.apply(
        suggestion, context, currentItem.followUpLimit());
    if (decision.nextStep() == NextStep.NEXT_TOPIC
        && same(decision.targetCompetency(), context.currentCompetency())
        && context.followUpCount() >= currentItem.followUpLimit()) {
      decision = nextAfterExhaustedItem(plan, context, decision);
    }
    if (decision.nextStep() == NextStep.FINISH) {
      return new StrategyOutcome(decision, null);
    }
    Difficulty nextDifficulty = adjust(context.currentDifficulty(), decision.difficultyAdjustment());
    InterviewPlanItem item = plan.itemFor(decision.targetCompetency());
    boolean continuingCurrent = same(decision.targetCompetency(), context.currentCompetency());
    int modeIndex = decision.nextStep() == NextStep.FOLLOW_UP || continuingCurrent
        ? context.followUpCount() + 1 : 0;
    String probeFocus = decision.nextStep() == NextStep.FOLLOW_UP || continuingCurrent
        ? evidenceGap(item, assessment, context.followUpCount())
        : item.evidenceTargets().getFirst();
    return new StrategyOutcome(
        decision, directive(item, nextDifficulty, modeIndex, probeFocus, decision.reason()));
  }

  private TurnDirective directive(
      InterviewPlanItem item, Difficulty difficulty, int modeIndex,
      String probeFocus, String reason) {
    InterviewQuestionMode mode = item.questionModes().get(
        Math.min(modeIndex, item.questionModes().size() - 1));
    return new TurnDirective(
        item.stageId(), item.competency(), difficulty, item.evidenceTargets(),
        mode, item.ragEnabled(), probeFocus, reason, item.resumeEntryPoint(),
        item.retrievalPolicy());
  }

  private InterviewDecision nextAfterExhaustedItem(
      InterviewPlan plan, DecisionContext context, InterviewDecision previous) {
    return plan.competencies().stream()
        .filter(candidate -> !same(candidate, context.currentCompetency()))
        .filter(candidate -> context.coveredCompetencies().stream()
            .noneMatch(covered -> same(candidate, covered)))
        .findFirst()
        .map(target -> new InterviewDecision(
            NextStep.NEXT_TOPIC, previous.difficultyAdjustment(), target, "",
            "CURRENT_ITEM_BUDGET_EXHAUSTED", previous.confidence()))
        .orElseGet(() -> context.currentTurn() >= context.totalTurnBudget()
            ? new InterviewDecision(
                NextStep.FINISH, DifficultyAdjustment.KEEP, "", "",
                "TURN_BUDGET_EXHAUSTED", previous.confidence())
            : new InterviewDecision(
                NextStep.NEXT_TOPIC, DifficultyAdjustment.KEEP,
                context.currentCompetency(), "",
                "LAST_REQUIRED_EVIDENCE_ATTEMPT", previous.confidence()));
  }

  private String evidenceGap(
      InterviewPlanItem item, AnswerEvaluation assessment, int followUpCount) {
    for (String target : item.evidenceTargets()) {
      if (assessment.missingPoints().stream()
          .anyMatch(missing -> CompetencyMatcher.related(target, missing))) {
        return withAxis(target, item, followUpCount);
      }
    }
    if (!item.evidenceTargets().isEmpty()) {
      String target = item.evidenceTargets().get(
          Math.min(followUpCount, item.evidenceTargets().size() - 1));
      return withAxis(target, item, followUpCount);
    }
    return "补充可核验的工程证据";
  }

  private String withAxis(String target, InterviewPlanItem item, int followUpCount) {
    if (item.followUpAxes().isEmpty()) return target;
    String axis = item.followUpAxes().get(
        Math.min(followUpCount, item.followUpAxes().size() - 1));
    return target + "；追问角度：" + axis;
  }

  private InterviewDecision trustedSuggestion(InterviewDecision suggestion, InterviewPlan plan) {
    if (suggestion != null && suggestion.nextStep() == NextStep.NEXT_TOPIC
        && plan.competencies().stream().noneMatch(
            allowed -> same(allowed, suggestion.targetCompetency()))) {
      return null;
    }
    return suggestion;
  }

  private Difficulty adjust(Difficulty current, DifficultyAdjustment adjustment) {
    return switch (adjustment) {
      case KEEP -> current;
      case INCREASE -> current == Difficulty.EASY ? Difficulty.MEDIUM : Difficulty.HARD;
      case DECREASE -> current == Difficulty.HARD ? Difficulty.MEDIUM : Difficulty.EASY;
    };
  }

  private boolean same(String left, String right) {
    return CompetencyMatcher.same(left, right);
  }
}
