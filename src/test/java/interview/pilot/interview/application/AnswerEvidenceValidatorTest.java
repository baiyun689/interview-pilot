package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;

class AnswerEvidenceValidatorTest {
  private final AnswerEvidenceValidator validator = new AnswerEvidenceValidator();

  @Test
  void removesEvidenceThatCannotBeGroundedInTheCurrentAnswer() {
    AnswerEvaluation evaluation = new AnswerEvaluation(
        80, "ok", List.of("使用 Redis Lua 保证原子更新", "通过 Kafka 实现削峰"),
        List.of(), List.of("缺少容量指标"),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Redis", "边界", "继续", 0.9));

    AnswerEvaluation validated = validator.validate(
        "我使用 Redis Lua 脚本把读取和更新放在一次原子操作里。", evaluation);

    assertThat(validated.evidence()).containsExactly("使用 Redis Lua 保证原子更新");
    assertThat(validated.missingPoints()).anyMatch(value -> value.contains("Kafka"));
    assertThat(validated.redFlags()).containsExactly("缺少容量指标");
  }

  @Test
  void doesNotAcceptAComplexClaimFromOneSharedTechnologyName() {
    AnswerEvaluation evaluation = new AnswerEvaluation(
        70, "partial", List.of("Kafka 完成削峰并保证端到端一致性"), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Kafka", "一致性", "继续", 0.8));

    AnswerEvaluation validated = validator.validate("项目里使用过 Kafka。", evaluation);

    assertThat(validated.evidence()).isEmpty();
  }

  @Test
  void exactClaimsContainingConcurrencyWordsAreNotSplitAsConjunctions() {
    AnswerEvaluation evaluation = new AnswerEvaluation(
        75, "ok", List.of("采用并发处理"), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Java", "并发", "继续", 0.8));

    assertThat(validator.validate("该任务采用并发处理。", evaluation).evidence())
        .containsExactly("采用并发处理");
  }

  @Test
  void keepsReferenceFactsAndAssessmentsWhenRebuildingEvaluation() {
    AnswerEvaluation evaluation = new AnswerEvaluation(
        80, "ok", List.of("使用 Redis Lua 保证原子更新", "通过 Kafka 实现削峰"),
        List.of(), List.of("缺少容量指标"),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Redis", "边界", "继续", 0.9),
        List.of(new AnswerEvaluation.ReferenceFact("point-1", "参考事实")),
        List.of(),
        List.of(new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "依据")));

    AnswerEvaluation validated = validator.validate(
        "我使用 Redis Lua 脚本把读取和更新放在一次原子操作里。", evaluation);

    assertThat(validated.referenceFacts()).isEqualTo(evaluation.referenceFacts());
    assertThat(validated.evidenceAssessments()).isEqualTo(evaluation.evidenceAssessments());
  }
}
