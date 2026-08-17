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
}
