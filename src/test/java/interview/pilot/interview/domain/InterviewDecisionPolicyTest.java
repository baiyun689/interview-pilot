package interview.pilot.interview.domain;

import static interview.pilot.interview.domain.DifficultyAdjustment.DECREASE;
import static interview.pilot.interview.domain.DifficultyAdjustment.INCREASE;
import static interview.pilot.interview.domain.DifficultyAdjustment.KEEP;
import static interview.pilot.interview.domain.NextStep.FINISH;
import static interview.pilot.interview.domain.NextStep.FOLLOW_UP;
import static interview.pilot.interview.domain.NextStep.NEXT_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewDecisionPolicyTest {
  private final InterviewDecisionPolicy policy = new InterviewDecisionPolicy();

  @Test
  void keepsFollowUpAndDifficultyAdjustmentOrthogonal() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of(), List.of("Java", "MySQL"), 0, 1, 8);
    InterviewDecision followUpIncrease = suggestion(FOLLOW_UP, INCREASE, "MySQL", "并发原理", 0.9);

    InterviewDecision normalized = policy.apply(followUpIncrease, context);

    assertThat(policy.apply(followUpIncrease, context).nextStep()).isEqualTo(FOLLOW_UP);
    assertThat(policy.apply(followUpIncrease, context).difficultyAdjustment()).isEqualTo(INCREASE);
    assertThat(normalized.targetCompetency()).isEqualTo("Java");
    assertThat(normalized.probeFocus()).isEqualTo("并发原理");
    assertThat(normalized.reason()).isEqualTo("FOLLOW_UP_CURRENT_COMPETENCY");
  }

  @Test
  void keepsNextTopicAndDifficultyAdjustmentOrthogonal() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of("Java"), List.of("Java", "MySQL"), 0, 1, 8);

    InterviewDecision normalized = policy.apply(suggestion(NEXT_TOPIC, INCREASE, "MySQL", "索引", 0.9), context);

    assertThat(normalized.nextStep()).isEqualTo(NEXT_TOPIC);
    assertThat(normalized.difficultyAdjustment()).isEqualTo(INCREASE);
    assertThat(normalized.targetCompetency()).isEqualTo("MySQL");
    assertThat(normalized.reason()).isEqualTo("NEXT_UNCOVERED_REQUIRED_COMPETENCY");
  }

  @Test
  void acceptsAnUncoveredSuggestedTopicAfterRequiredCompetenciesAreCovered() {
    DecisionContext context = context(
        Difficulty.MEDIUM,
        "Java",
        List.of("Java", "MySQL"),
        List.of("Java", "MySQL"),
        0,
        3,
        8);

    InterviewDecision normalized = policy.apply(
        suggestion(NEXT_TOPIC, INCREASE, "Kafka", "消费者再平衡", 0.9), context);

    assertThat(normalized.nextStep()).isEqualTo(NEXT_TOPIC);
    assertThat(normalized.difficultyAdjustment()).isEqualTo(INCREASE);
    assertThat(normalized.targetCompetency()).isEqualTo("Kafka");
    assertThat(normalized.probeFocus()).isEqualTo("消费者再平衡");
    assertThat(normalized.reason()).isEqualTo("NEXT_UNCOVERED_SUGGESTED_COMPETENCY");
  }

  @Test
  void acceptsDifficultyDecreaseForAValidFollowUp() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of(), List.of("Java"), 0, 1, 8);

    InterviewDecision normalized = policy.apply(
        suggestion(FOLLOW_UP, DECREASE, "Java", "基础语义", 0.9), context);

    assertThat(normalized.nextStep()).isEqualTo(FOLLOW_UP);
    assertThat(normalized.difficultyAdjustment()).isEqualTo(DECREASE);
  }

  @Test
  void finishAlwaysKeepsDifficulty() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of("Java"), List.of("Java"), 0, 2, 8);
    InterviewDecision finishIncrease = suggestion(FINISH, INCREASE, "", "", 0.9);

    assertThat(policy.apply(finishIncrease, context).difficultyAdjustment()).isEqualTo(KEEP);
    assertThat(policy.apply(finishIncrease, context).targetCompetency()).isEmpty();
    assertThat(policy.apply(finishIncrease, context).probeFocus()).isEmpty();
    assertThat(policy.apply(finishIncrease, context).reason()).isEqualTo("MODEL_FINISH_ACCEPTED");
  }

  @Test
  void thirdFollowUpMovesToFirstUncoveredRequiredCompetency() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of("Java"), List.of("Java", "MySQL"), 2, 3, 8);
    InterviewDecision thirdFollowUp = suggestion(FOLLOW_UP, DECREASE, "Java", "继续追问", 0.9);

    InterviewDecision normalized = policy.apply(thirdFollowUp, context);

    assertThat(policy.apply(thirdFollowUp, context).nextStep()).isEqualTo(NEXT_TOPIC);
    assertThat(normalized.targetCompetency()).isEqualTo("MySQL");
    assertThat(normalized.difficultyAdjustment()).isEqualTo(DECREASE);
    assertThat(normalized.reason()).isEqualTo("FOLLOW_UP_LIMIT_REACHED");
  }

  @Test
  void thirdFollowUpFinishesWhenNoUncoveredCompetencyExists() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of("Java"), List.of("Java"), 2, 3, 8);

    InterviewDecision normalized = policy.apply(suggestion(FOLLOW_UP, INCREASE, "Java", "继续", 0.9), context);

    assertThat(normalized.nextStep()).isEqualTo(FINISH);
    assertThat(normalized.difficultyAdjustment()).isEqualTo(KEEP);
    assertThat(normalized.reason()).isEqualTo("NO_UNCOVERED_COMPETENCY");
  }

  @Test
  void preventsDifficultyChangesOutsideBounds() {
    DecisionContext seniorContext = context(Difficulty.HARD, "Java", List.of(), List.of("Java"), 0, 1, 8);
    InterviewDecision outOfRangeIncrease = suggestion(FOLLOW_UP, INCREASE, "Java", "JMM", 0.9);
    DecisionContext easyContext = context(Difficulty.EASY, "Java", List.of(), List.of("Java"), 0, 1, 8);

    assertThat(policy.apply(outOfRangeIncrease, seniorContext).difficultyAdjustment()).isEqualTo(KEEP);
    assertThat(policy.apply(suggestion(FOLLOW_UP, DECREASE, "Java", "基础", 0.9), easyContext).difficultyAdjustment()).isEqualTo(KEEP);
  }

  @Test
  void fillsMissingOrCoveredNextTopicTargetFromRequiredOrder() {
    DecisionContext context = context(
        Difficulty.MEDIUM,
        "Java",
        List.of(" java ", "Redis"),
        List.of("Java", "", "MySQL", "mysql", "Redis"),
        0,
        2,
        8);

    InterviewDecision missingTarget = policy.apply(suggestion(NEXT_TOPIC, KEEP, null, "事务", 0.9), context);
    InterviewDecision coveredTarget = policy.apply(suggestion(NEXT_TOPIC, KEEP, "Redis", "事务", 0.9), context);

    assertThat(missingTarget.targetCompetency()).isEqualTo("MySQL");
    assertThat(coveredTarget.targetCompetency()).isEqualTo("MySQL");
  }

  @Test
  void missingOrCoveredNextTopicTargetFinishesWithoutARequiredFallback() {
    DecisionContext context = context(
        Difficulty.MEDIUM,
        "Java",
        List.of("Java", "MySQL"),
        List.of("Java", "MySQL"),
        0,
        2,
        8);

    for (String target : List.of("", "MySQL")) {
      InterviewDecision normalized = policy.apply(
          suggestion(NEXT_TOPIC, INCREASE, target, "ignored", 0.9), context);
      assertThat(normalized.nextStep()).isEqualTo(FINISH);
      assertThat(normalized.difficultyAdjustment()).isEqualTo(KEEP);
      assertThat(normalized.targetCompetency()).isEmpty();
      assertThat(normalized.reason()).isEqualTo("NO_UNCOVERED_COMPETENCY");
    }
  }

  @Test
  void finishSuggestionCannotSkipRequiredJdCompetency() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of("Java"), List.of("Java", "MySQL", "Redis"), 0, 2, 8);

    InterviewDecision normalized = policy.apply(suggestion(FINISH, KEEP, "", "", 0.9), context);

    assertThat(normalized.nextStep()).isEqualTo(NEXT_TOPIC);
    assertThat(normalized.targetCompetency()).isEqualTo("MySQL");
    assertThat(normalized.difficultyAdjustment()).isEqualTo(KEEP);
    assertThat(normalized.reason()).isEqualTo("REQUIRED_COMPETENCY_NOT_COVERED");
  }

  @Test
  void exhaustedOrBoundaryBudgetAlwaysFinishesAndKeepsDifficulty() {
    DecisionContext zeroBudget = context(Difficulty.MEDIUM, "Java", List.of(), List.of("Java"), 0, 0, 0);
    DecisionContext atBudget = context(Difficulty.MEDIUM, "Java", List.of(), List.of("Java"), 0, 5, 5);
    DecisionContext overBudget = context(Difficulty.MEDIUM, "Java", List.of(), List.of("Java"), 0, 6, 5);

    for (DecisionContext context : List.of(zeroBudget, atBudget, overBudget)) {
      InterviewDecision normalized = policy.apply(suggestion(FOLLOW_UP, INCREASE, "Java", "JMM", 0.9), context);
      assertThat(normalized.nextStep()).isEqualTo(FINISH);
      assertThat(normalized.difficultyAdjustment()).isEqualTo(KEEP);
      assertThat(normalized.targetCompetency()).isEmpty();
      assertThat(normalized.reason()).isEqualTo("TURN_BUDGET_EXHAUSTED");
    }

    InterviewDecision invalidConfidence = policy.apply(
        suggestion(FOLLOW_UP, INCREASE, "Java", "JMM", 2.0), atBudget);
    assertThat(invalidConfidence.confidence()).isZero();
  }

  @Test
  void lowOrNonFiniteConfidenceUsesDeterministicRequiredCompetencyDefault() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of("Java"), List.of("Java", "MySQL"), 0, 2, 8);

    for (double confidence : List.of(0.49, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      InterviewDecision normalized = policy.apply(suggestion(FOLLOW_UP, INCREASE, "Java", "ignored", confidence), context);
      assertThat(normalized.nextStep()).isEqualTo(NEXT_TOPIC);
      assertThat(normalized.difficultyAdjustment()).isEqualTo(KEEP);
      assertThat(normalized.targetCompetency()).isEqualTo("MySQL");
      assertThat(normalized.probeFocus()).isEmpty();
      assertThat(normalized.reason()).isEqualTo("INVALID_OR_LOW_CONFIDENCE");
    }
  }

  @Test
  void nullSuggestionAndIllegalDecisionFieldsUseTheSameDeterministicDefault() {
    DecisionContext context = context(Difficulty.MEDIUM, "Java", List.of(), List.of("Java", "MySQL"), 0, 1, 8);

    InterviewDecision fromNull = policy.apply(null, context);
    InterviewDecision fromIllegalFields = policy.apply(new InterviewDecision(null, null, null, null, null, 0.9), context);

    assertThat(fromNull).isEqualTo(fromIllegalFields);
    assertThat(fromNull.nextStep()).isEqualTo(NEXT_TOPIC);
    assertThat(fromNull.targetCompetency()).isEqualTo("Java");
    assertThat(fromNull.reason()).isEqualTo("INVALID_OR_LOW_CONFIDENCE");
  }

  @Test
  void deterministicDefaultFinishesWhenNothingCanBeCovered() {
    DecisionContext context = context(Difficulty.MEDIUM, "", List.of(), List.of(), 0, 1, 8);

    InterviewDecision normalized = policy.apply(null, context);

    assertThat(normalized.nextStep()).isEqualTo(FINISH);
    assertThat(normalized.difficultyAdjustment()).isEqualTo(KEEP);
    assertThat(normalized.reason()).isEqualTo("NO_UNCOVERED_COMPETENCY");
  }

  @Test
  void contextNormalizesAndDefensivelyCopiesCompetencies() {
    ArrayList<String> required = new ArrayList<>(List.of(" Java ", "java", "", "MySQL"));
    ArrayList<String> covered = new ArrayList<>(List.of(" Redis ", "redis"));

    DecisionContext context = context(Difficulty.MEDIUM, " Java ", covered, required, 0, 1, 8);
    required.add("RabbitMQ");
    covered.add("MySQL");

    assertThat(context.currentCompetency()).isEqualTo("Java");
    assertThat(context.requiredCompetencies()).containsExactly("Java", "MySQL");
    assertThat(context.coveredCompetencies()).containsExactly("Redis");
    assertThatThrownBy(() -> context.requiredCompetencies().add("RabbitMQ"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void contextRejectsInvalidInvariants() {
    assertThatThrownBy(() -> context(null, "Java", List.of(), List.of(), 0, 1, 8))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> context(Difficulty.MEDIUM, "Java", List.of(), List.of(), -1, 1, 8))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> context(Difficulty.MEDIUM, "Java", List.of(), List.of(), 0, -1, 8))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> context(Difficulty.MEDIUM, "Java", List.of(), List.of(), 0, 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DecisionContext(Difficulty.MEDIUM, "Java", List.of(), List.of(), 0, 1, 8, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DecisionContext(Difficulty.MEDIUM, "Java", List.of(), List.of(), 0, 1, 8, 1.1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void planAndEvaluationOwnImmutableNormalizedCollections() {
    ArrayList<String> competencies = new ArrayList<>(List.of(" Java ", "java", "MySQL"));
    InterviewPlan plan = new InterviewPlan(competencies, 8);
    ArrayList<String> evidence = new ArrayList<>(List.of(" evidence "));
    ArrayList<String> missing = new ArrayList<>(List.of(" transactions "));
    AnswerEvaluation evaluation = new AnswerEvaluation(82.5, " clear ", evidence, missing, suggestion(FOLLOW_UP, KEEP, "Java", "JMM", 0.9));

    competencies.add("Redis");
    evidence.add("late mutation");
    missing.add("late mutation");

    assertThat(plan.competencies()).containsExactly("Java", "MySQL");
    assertThat(evaluation.feedback()).isEqualTo("clear");
    assertThat(evaluation.evidence()).containsExactly("evidence");
    assertThat(evaluation.missingPoints()).containsExactly("transactions");
    assertThatThrownBy(() -> plan.competencies().add("Redis")).isInstanceOf(UnsupportedOperationException.class);
  }

  private DecisionContext context(
      Difficulty difficulty,
      String currentCompetency,
      List<String> covered,
      List<String> required,
      int followUpCount,
      int currentTurn,
      int totalBudget) {
    return new DecisionContext(difficulty, currentCompetency, covered, required, followUpCount, currentTurn, totalBudget, 0.5);
  }

  private InterviewDecision suggestion(
      NextStep nextStep,
      DifficultyAdjustment difficultyAdjustment,
      String targetCompetency,
      String probeFocus,
      double confidence) {
    return new InterviewDecision(nextStep, difficultyAdjustment, targetCompetency, probeFocus, "model reason", confidence);
  }
}
