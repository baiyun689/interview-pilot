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
        new Step(QuestionSpeechStatus.SYNTHESIZING, QuestionSpeechStatus.READY),
        new Step(QuestionSpeechStatus.SYNTHESIZING, QuestionSpeechStatus.FAILED),
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
