package interview.pilot.interview.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.InputMode;

class SubmitAnswerRequestTest {

  @Test
  void absentInputModeDefaultsToTextAndKeepsNullRecordingId() {
    var request = new SubmitAnswerRequest(UUID.randomUUID(), "  回答  ");

    assertThat(request.inputMode()).isEqualTo(InputMode.TEXT);
    assertThat(request.recordingId()).isNull();
    assertThat(request.answer()).isEqualTo("回答");
  }

  @Test
  void voiceSubmissionKeepsTheModeAndRecordingId() {
    UUID recordingId = UUID.randomUUID();

    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "回答", InputMode.VOICE, recordingId);

    assertThat(request.inputMode()).isEqualTo(InputMode.VOICE);
    assertThat(request.recordingId()).isEqualTo(recordingId);
  }

  @Test
  void textFallbackInAVoiceSessionOmitsTheRecordingId() {
    var request = new SubmitAnswerRequest(
        UUID.randomUUID(), "文字回退", InputMode.TEXT, null);

    assertThat(request.inputMode()).isEqualTo(InputMode.TEXT);
    assertThat(request.recordingId()).isNull();
  }
}
