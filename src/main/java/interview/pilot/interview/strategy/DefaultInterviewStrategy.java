package interview.pilot.interview.strategy;

import java.util.Locale;

import interview.pilot.interview.domain.AnswerEvaluation;
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
    return directive(item, difficulty, 0, "PLAN_FIRST_TURN");
  }

  @Override
  public StrategyOutcome nextTurn(
      InterviewPlan plan, DecisionContext context, AnswerEvaluation assessment) {
    InterviewDecision suggestion = trustedSuggestion(assessment.suggestedDecision(), plan);
    InterviewDecision decision = decisionPolicy.apply(suggestion, context);
    if (decision.nextStep() == NextStep.FINISH) {
      return new StrategyOutcome(decision, null);
    }
    Difficulty nextDifficulty = adjust(context.currentDifficulty(), decision.difficultyAdjustment());
    InterviewPlanItem item = plan.itemFor(decision.targetCompetency());
    int modeIndex = decision.nextStep() == NextStep.FOLLOW_UP ? context.followUpCount() + 1 : 0;
    return new StrategyOutcome(
        decision, directive(item, nextDifficulty, modeIndex, decision.reason()));
  }

  private TurnDirective directive(
      InterviewPlanItem item, Difficulty difficulty, int modeIndex, String reason) {
    InterviewQuestionMode mode = item.questionModes().get(
        Math.min(modeIndex, item.questionModes().size() - 1));
    return new TurnDirective(
        item.stageId(), item.competency(), difficulty, item.evidenceTargets(),
        mode, item.ragEnabled(), reason);
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
    return key(left).equals(key(right));
  }

  private String key(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
