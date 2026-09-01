package interview.pilot.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionSpeechStatusTest {
  private record Step(QuestionSpeechStatus from, QuestionSpeechStatus to) {}

  @Test
  void allowsOnlyTheDocumentedLegalTransitions() {
    List<Step> legal = List.of(
        new Step(QuestionSpeechStatus.PENDING, QuestionSpeechStatus.SYNTHESIZING),
        // PENDING → FAILED: the markDead path when retries die before the claim transaction
        // (Task 7, mirror of the recording's pre-approved UPLOADED → FAILED transition).
        new Step(QuestionSpeechStatus.PENDING, QuestionSpeechStatus.FAILED),
        new Step(QuestionSpeechStatus.SYNTHESIZING, QuestionSpeechStatus.READY),
        new Step(QuestionSpeechStatus.SYNTHESIZING, QuestionSpeechStatus.FAILED),
        // FAILED → PENDING belongs to Task 8's manual retry — deliberately not exercised yet.
        new Step(QuestionSpeechStatus.FAILED, QuestionSpeechStatus.PENDING));

    for (QuestionSpeechStatus from : QuestionSpeechStatus.values()) {
      for (QuestionSpeechStatus to : QuestionSpeechStatus.values()) {
        boolean documented = legal.contains(new Step(from, to));
        assertThat(from.canTransitionTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(documented);
      }
    }
  }
}
