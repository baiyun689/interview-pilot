package interview.pilot.interview.domain;

import static interview.pilot.interview.domain.DifficultyAdjustment.INCREASE;
import static interview.pilot.interview.domain.DifficultyAdjustment.KEEP;
import static interview.pilot.interview.domain.NextStep.FINISH;
import static interview.pilot.interview.domain.NextStep.FOLLOW_UP;
import static interview.pilot.interview.domain.NextStep.NEXT_TOPIC;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class InterviewDecisionPolicy {
  private static final int MAX_FOLLOW_UPS = 2;

  public InterviewDecision apply(InterviewDecision suggestion, DecisionContext context) {
    Objects.requireNonNull(context, "context must not be null");

    if (context.currentTurn() >= context.totalTurnBudget()) {
      return finish("TURN_BUDGET_EXHAUSTED", confidenceOf(suggestion));
    }

    if (isInvalid(suggestion, context)) {
      return deterministicDefault(context, "INVALID_OR_LOW_CONFIDENCE");
    }

    return switch (suggestion.nextStep()) {
      case FINISH -> normalizeFinish(suggestion, context);
      case FOLLOW_UP -> normalizeFollowUp(suggestion, context);
      case NEXT_TOPIC -> normalizeNextTopic(suggestion, context);
    };
  }

  private InterviewDecision normalizeFinish(InterviewDecision suggestion, DecisionContext context) {
    Optional<String> uncovered = firstUncoveredRequired(context);
    if (uncovered.isPresent()) {
      return nextTopic(
          uncovered.get(),
          "",
          "REQUIRED_COMPETENCY_NOT_COVERED",
          KEEP,
          suggestion.confidence());
    }
    return finish("MODEL_FINISH_ACCEPTED", suggestion.confidence());
  }

  private InterviewDecision normalizeFollowUp(InterviewDecision suggestion, DecisionContext context) {
    if (context.followUpCount() >= MAX_FOLLOW_UPS) {
      return firstUncoveredRequired(context)
          .map(target -> nextTopic(
              target,
              "",
              "FOLLOW_UP_LIMIT_REACHED",
              bounded(suggestion.difficultyAdjustment(), context.currentDifficulty()),
              suggestion.confidence()))
          .orElseGet(() -> finish("NO_UNCOVERED_COMPETENCY", suggestion.confidence()));
    }
    if (context.currentCompetency().isEmpty()) {
      return firstUncoveredRequired(context)
          .map(target -> nextTopic(
              target,
              "",
              "MISSING_CURRENT_COMPETENCY",
              bounded(suggestion.difficultyAdjustment(), context.currentDifficulty()),
              suggestion.confidence()))
          .orElseGet(() -> finish("NO_UNCOVERED_COMPETENCY", suggestion.confidence()));
    }
    return new InterviewDecision(
        FOLLOW_UP,
        bounded(suggestion.difficultyAdjustment(), context.currentDifficulty()),
        context.currentCompetency(),
        suggestion.probeFocus(),
        "FOLLOW_UP_CURRENT_COMPETENCY",
        suggestion.confidence());
  }

  private InterviewDecision normalizeNextTopic(InterviewDecision suggestion, DecisionContext context) {
    Optional<String> uncovered = firstUncoveredRequired(context);
    if (uncovered.isPresent()) {
      String target = uncovered.get();
      String probeFocus = sameCompetency(target, suggestion.targetCompetency())
          ? suggestion.probeFocus()
          : "";
      return nextTopic(
          target,
          probeFocus,
          "NEXT_UNCOVERED_REQUIRED_COMPETENCY",
          bounded(suggestion.difficultyAdjustment(), context.currentDifficulty()),
          suggestion.confidence());
    }

    String target = suggestion.targetCompetency();
    if (target.isEmpty() || isCovered(target, context)) {
      return finish("NO_UNCOVERED_COMPETENCY", suggestion.confidence());
    }
    return nextTopic(
        target,
        suggestion.probeFocus(),
        "NEXT_UNCOVERED_SUGGESTED_COMPETENCY",
        bounded(suggestion.difficultyAdjustment(), context.currentDifficulty()),
        suggestion.confidence());
  }

  private InterviewDecision deterministicDefault(DecisionContext context, String reason) {
    return firstUncoveredRequired(context)
        .map(target -> nextTopic(target, "", reason, KEEP, 0))
        .orElseGet(() -> finish("NO_UNCOVERED_COMPETENCY", 0));
  }

  private Optional<String> firstUncoveredRequired(DecisionContext context) {
    return context.requiredCompetencies().stream()
        .filter(required -> !isCovered(required, context))
        .findFirst();
  }

  private boolean isCovered(String competency, DecisionContext context) {
    return context.coveredCompetencies().stream()
        .anyMatch(covered -> sameCompetency(competency, covered));
  }

  private boolean isInvalid(InterviewDecision suggestion, DecisionContext context) {
    return suggestion == null
        || suggestion.nextStep() == null
        || suggestion.difficultyAdjustment() == null
        || !Double.isFinite(suggestion.confidence())
        || suggestion.confidence() < context.minimumConfidence()
        || suggestion.confidence() > 1;
  }

  private DifficultyAdjustment bounded(
      DifficultyAdjustment adjustment, Difficulty currentDifficulty) {
    if ((adjustment == INCREASE && currentDifficulty == Difficulty.HARD)
        || (adjustment == DifficultyAdjustment.DECREASE && currentDifficulty == Difficulty.EASY)) {
      return KEEP;
    }
    return adjustment;
  }

  private InterviewDecision nextTopic(
      String target,
      String probeFocus,
      String reason,
      DifficultyAdjustment adjustment,
      double confidence) {
    return new InterviewDecision(NEXT_TOPIC, adjustment, target, probeFocus, reason, confidence);
  }

  private InterviewDecision finish(String reason, double confidence) {
    return new InterviewDecision(FINISH, KEEP, "", "", reason, confidence);
  }

  private double confidenceOf(InterviewDecision suggestion) {
    return suggestion != null
            && Double.isFinite(suggestion.confidence())
            && suggestion.confidence() >= 0
            && suggestion.confidence() <= 1
        ? suggestion.confidence()
        : 0;
  }

  private boolean sameCompetency(String left, String right) {
    return key(left).equals(key(right));
  }

  private String key(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
