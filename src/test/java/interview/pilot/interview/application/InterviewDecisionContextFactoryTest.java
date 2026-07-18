package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.application.InterviewDecisionContextFactory.CompletedTurnEvidence;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;

class InterviewDecisionContextFactoryTest {
  private final InterviewDecisionContextFactory factory = new InterviewDecisionContextFactory();

  @Test
  void onlyCompletedAnswersWithScoreAndEvidenceCountAsCovered() {
    var completed = List.of(
        new CompletedTurnEvidence("Java", 85, List.of()),
        new CompletedTurnEvidence("MySQL", 59, List.of("index explanation")),
        new CompletedTurnEvidence("Redis", 60, List.of("cache invalidation evidence")));
    AnswerEvaluation current = evaluation(90, List.of());

    var context = factory.create(
        Difficulty.MEDIUM, "Spring", List.of("Java", "MySQL", "Redis", "Spring"),
        4, 8, 0.55, completed, current);

    assertThat(context.coveredCompetencies()).containsExactly("Redis");
  }

  @Test
  void currentAnsweredEvidenceCanCoverItsCompetency() {
    var context = factory.create(
        Difficulty.MEDIUM, "Spring", List.of("Spring"), 1, 5, 0.55, List.of(),
        evaluation(60, List.of("transaction boundary")));

    assertThat(context.coveredCompetencies()).containsExactly("Spring");
  }

  @Test
  void followUpCountUsesOnlyTheConsecutiveSameCompetencySuffix() {
    var completed = List.of(
        new CompletedTurnEvidence("Java", 80, List.of("a")),
        new CompletedTurnEvidence("Spring", 80, List.of("b")),
        new CompletedTurnEvidence("Java", 80, List.of("c")),
        new CompletedTurnEvidence(" java ", 80, List.of("d")));

    var java = factory.create(
        Difficulty.MEDIUM, "JAVA", List.of("Java"), 5, 8, 0.55, completed,
        evaluation(70, List.of("e")));
    var spring = factory.create(
        Difficulty.MEDIUM, "Spring", List.of("Spring"), 5, 8, 0.55, completed,
        evaluation(70, List.of("e")));

    assertThat(java.followUpCount()).isEqualTo(2);
    assertThat(spring.followUpCount()).isZero();
  }

  private AnswerEvaluation evaluation(double score, List<String> evidence) {
    return new AnswerEvaluation(
        score, "feedback", evidence, List.of(),
        new InterviewDecision(
            NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP, "", "", "", 0.9));
  }
}
