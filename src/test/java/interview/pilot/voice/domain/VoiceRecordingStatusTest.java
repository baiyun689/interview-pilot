package interview.pilot.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VoiceRecordingStatusTest {
  private record Step(VoiceRecordingStatus from, VoiceRecordingStatus to) {}

  @Test
  void allowsOnlyTheDocumentedLegalTransitions() {
    List<Step> legal = List.of(
        new Step(VoiceRecordingStatus.RECEIVING, VoiceRecordingStatus.UPLOADED),
        new Step(VoiceRecordingStatus.RECEIVING, VoiceRecordingStatus.FAILED),
        new Step(VoiceRecordingStatus.RECEIVING, VoiceRecordingStatus.DISCARDED),
        new Step(VoiceRecordingStatus.UPLOADED, VoiceRecordingStatus.TRANSCRIBING),
        new Step(VoiceRecordingStatus.UPLOADED, VoiceRecordingStatus.FAILED),
        new Step(VoiceRecordingStatus.TRANSCRIBING, VoiceRecordingStatus.READY),
        new Step(VoiceRecordingStatus.TRANSCRIBING, VoiceRecordingStatus.FAILED),
        new Step(VoiceRecordingStatus.READY, VoiceRecordingStatus.ATTACHED),
        new Step(VoiceRecordingStatus.READY, VoiceRecordingStatus.DISCARDED),
        new Step(VoiceRecordingStatus.FAILED, VoiceRecordingStatus.TRANSCRIBING),
        new Step(VoiceRecordingStatus.FAILED, VoiceRecordingStatus.DISCARDED));

    for (VoiceRecordingStatus from : VoiceRecordingStatus.values()) {
      for (VoiceRecordingStatus to : VoiceRecordingStatus.values()) {
        boolean documented = legal.contains(new Step(from, to));
        assertThat(from.canTransitionTo(to))
            .as("%s -> %s", from, to)
            .isEqualTo(documented);
      }
    }
  }
}
