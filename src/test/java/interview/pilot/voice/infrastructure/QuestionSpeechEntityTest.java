package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.voice.domain.QuestionSpeechStatus;

class QuestionSpeechEntityTest {
  private final QuestionSpeechEntity speech = QuestionSpeechEntity.pending(
      1L, UUID.randomUUID(), 7L, 9L, "abc123", "dashscope", "cosyvoice-v3-flash", "longanyang");

  @Test
  void pendingRecordsTheConfigSnapshotAndTheTextDigest() {
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(speech.getSpeechId()).isNotNull();
    assertThat(speech.getTextSha256()).isEqualTo("abc123");
    assertThat(speech.getProviderId()).isEqualTo("dashscope");
    assertThat(speech.getModelName()).isEqualTo("cosyvoice-v3-flash");
    assertThat(speech.getVoiceName()).isEqualTo("longanyang");
    assertThat(speech.getExecutionEpoch()).isZero();
    assertThat(speech.getStorageKey()).isNull();
  }

  @Test
  void startSynthesisMovesPendingToSynthesizingWithoutTouchingTheEpoch() {
    speech.startSynthesis();

    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.SYNTHESIZING);
    assertThat(speech.getExecutionEpoch()).isZero();
    assertThat(speech.getStorageKey()).isNull();
  }

  @Test
  void completeSynthesisMovesSynthesizingToReadyWithTheResultMetadata() {
    speech.startSynthesis();

    speech.completeSynthesis("req-123", "key", "audio/mpeg", 2048, 3_200);

    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.READY);
    assertThat(speech.getProviderRequestId()).isEqualTo("req-123");
    assertThat(speech.getStorageKey()).isEqualTo("key");
    assertThat(speech.getContentType()).isEqualTo("audio/mpeg");
    assertThat(speech.getSizeBytes()).isEqualTo(2048);
    assertThat(speech.getDurationMillis()).isEqualTo(3_200);
    assertThat(speech.getSafeError()).isNull();
  }

  @Test
  void failSynthesisMovesSynthesizingToFailedWithSafeError() {
    speech.startSynthesis();

    speech.failSynthesis("VOICE_QUESTION_SPEECH_FAILED");

    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
    assertThat(speech.getSafeError()).isEqualTo("VOICE_QUESTION_SPEECH_FAILED");
    assertThat(speech.getStorageKey()).isNull();
  }

  @Test
  void failSynthesisCanTerminalizeAPendingSpeechBeforeTheClaim() {
    // The markDead path when retries die before the claim transaction ever ran (Task 7 —
    // mirror of the recording's pre-approved UPLOADED → FAILED transition).
    speech.failSynthesis("VOICE_QUESTION_SPEECH_FAILED");

    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
  }

  @Test
  void readyIsTerminalUntilTask8IntroducesTheRetryTransition() {
    speech.startSynthesis();
    speech.completeSynthesis("req", "key", "audio/mpeg", 1, 1);

    assertThatIllegalStateException().isThrownBy(speech::startSynthesis);
    assertThatIllegalStateException().isThrownBy(
        () -> speech.completeSynthesis("r", "k", "t", 1, 1));
    assertThatIllegalStateException().isThrownBy(
        () -> speech.failSynthesis("VOICE_QUESTION_SPEECH_FAILED"));
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.READY);
  }

  @Test
  void rejectsTransitionsOutsideTheStatusGraph() {
    speech.startSynthesis();
    speech.failSynthesis("VOICE_QUESTION_SPEECH_FAILED");

    assertThatIllegalStateException().isThrownBy(() -> speech.completeSynthesis("r", "k", "t", 1, 1));
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.FAILED);
  }
}
