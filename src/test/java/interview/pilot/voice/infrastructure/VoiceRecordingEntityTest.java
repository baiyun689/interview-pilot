package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.voice.domain.VoiceRecordingStatus;

class VoiceRecordingEntityTest {
  private final VoiceRecordingEntity recording =
      VoiceRecordingEntity.receiving(
          1L, UUID.randomUUID(), UUID.randomUUID(), 7L, 9L, Instant.now().plusSeconds(600));

  @Test
  void receivingRecordsTheIdempotencyKeyAndTheExpiry() {
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.RECEIVING);
    assertThat(recording.getExecutionEpoch()).isZero();
    assertThat(recording.getUploadRequestId()).isNotNull();
    assertThat(recording.getExpiresAt()).isNotNull();
  }

  @Test
  void acceptUploadMovesReceivingToUploadedWithImmutableMetadata() {
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(recording.getStorageKey()).isEqualTo("key");
    assertThat(recording.getContentType()).isEqualTo("audio/webm");
    assertThat(recording.getSizeBytes()).isEqualTo(1024);
    assertThat(recording.getDurationMillis()).isEqualTo(30_000);
    assertThat(recording.getSha256()).isEqualTo("abc");
  }

  @Test
  void failUploadMovesReceivingToFailedWithSafeErrorAndDigestWhenKnown() {
    recording.failUpload("VOICE_MEDIA_UNSUPPORTED", "digest");

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo("VOICE_MEDIA_UNSUPPORTED");
    assertThat(recording.getSha256()).isEqualTo("digest");
    assertThat(recording.getStorageKey()).isNull();
  }

  @Test
  void failUploadKeepsDigestNullWhenStreamingWasInterrupted() {
    recording.failUpload("VOICE_UPLOAD_TOO_LARGE", null);

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo("VOICE_UPLOAD_TOO_LARGE");
    assertThat(recording.getSha256()).isNull();
  }

  @Test
  void beginTranscriptionFencesTheExecutionEpochAndDiscardIsTerminal() {
    recording.failUpload("ASR_FAILED", null);
    recording.beginTranscription();

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    assertThat(recording.getExecutionEpoch()).isEqualTo(1);

    recording.moveTo(VoiceRecordingStatus.READY);
    recording.discard();
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);
  }

  @Test
  void startTranscriptionMovesUploadedToTranscribingWithoutTouchingTheEpoch() {
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");

    recording.startTranscription();

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.TRANSCRIBING);
    assertThat(recording.getExecutionEpoch()).isZero();
    assertThat(recording.getRawTranscript()).isNull();
  }

  @Test
  void completeTranscriptionMovesTranscribingToReadyWithTheResultMetadata() {
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");
    recording.startTranscription();

    recording.completeTranscription(
        "dashscope", "fun-asr-flash-2026-06-15", "req-123", "转写结果", 512L);

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.READY);
    assertThat(recording.getProviderId()).isEqualTo("dashscope");
    assertThat(recording.getModelName()).isEqualTo("fun-asr-flash-2026-06-15");
    assertThat(recording.getProviderRequestId()).isEqualTo("req-123");
    assertThat(recording.getRawTranscript()).isEqualTo("转写结果");
    assertThat(recording.getAsrDurationMillis()).isEqualTo(512L);
    assertThat(recording.getSafeError()).isNull();
  }

  @Test
  void failTranscriptionMovesUploadedOrTranscribingToFailedWithSafeError() {
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");

    recording.failTranscription("VOICE_TRANSCRIPTION_FAILED");

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.FAILED);
    assertThat(recording.getSafeError()).isEqualTo("VOICE_TRANSCRIPTION_FAILED");
  }

  @Test
  void beginTranscriptionRefusesToBumpTheEpochFromAnyNonFailedState() {
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");

    assertThatIllegalStateException().isThrownBy(recording::beginTranscription);

    recording.startTranscription();
    assertThatIllegalStateException().isThrownBy(recording::beginTranscription);
    assertThat(recording.getExecutionEpoch()).isZero();
  }

  @Test
  void attachMovesReadyToAttachedWithTheAnswerRequestId() {
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");
    recording.startTranscription();
    recording.completeTranscription("dashscope", "m", "r", "转写结果", 100L);
    UUID answerRequestId = UUID.randomUUID();

    recording.attach(answerRequestId);

    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.ATTACHED);
    assertThat(recording.getAttachedAnswerRequestId()).isEqualTo(answerRequestId);
    assertThat(recording.getRawTranscript()).isEqualTo("转写结果");
  }

  @Test
  void attachRefusesFromNonReadyStatesAndRequiresTheAnswerRequestId() {
    assertThatIllegalStateException().isThrownBy(() -> recording.attach(UUID.randomUUID()));
    recording.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");
    assertThatIllegalStateException().isThrownBy(() -> recording.attach(UUID.randomUUID()));
    recording.startTranscription();
    assertThatIllegalStateException().isThrownBy(() -> recording.attach(UUID.randomUUID()));
    recording.failTranscription("VOICE_TRANSCRIPTION_FAILED");
    assertThatIllegalStateException().isThrownBy(() -> recording.attach(UUID.randomUUID()));
    recording.discard();
    assertThatIllegalStateException().isThrownBy(() -> recording.attach(UUID.randomUUID()));
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);

    var ready = VoiceRecordingEntity.receiving(
        1L, UUID.randomUUID(), UUID.randomUUID(), 7L, 9L, Instant.now().plusSeconds(600));
    ready.acceptUpload("key", "audio/webm", 1024, 30_000, "abc");
    ready.startTranscription();
    ready.completeTranscription("dashscope", "m", "r", "t", 100L);
    assertThatThrownBy(() -> ready.attach(null))
        .isInstanceOf(NullPointerException.class);
    assertThat(ready.getStatus()).isEqualTo(VoiceRecordingStatus.READY);
  }

  @Test
  void rejectsTransitionsOutsideTheStatusGraph() {
    recording.moveTo(VoiceRecordingStatus.DISCARDED);

    assertThatIllegalStateException().isThrownBy(() -> recording.acceptUpload("k", "t", 1, 1, "s"));
    assertThatIllegalStateException().isThrownBy(() -> recording.failUpload("E", null));
    assertThatIllegalStateException().isThrownBy(() -> recording.beginTranscription());
    assertThatIllegalStateException().isThrownBy(() -> recording.discard());
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);
  }
}
