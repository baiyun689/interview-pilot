package interview.pilot.voice.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
  void rejectsTransitionsOutsideTheStatusGraph() {
    recording.moveTo(VoiceRecordingStatus.DISCARDED);

    assertThatIllegalStateException().isThrownBy(() -> recording.acceptUpload("k", "t", 1, 1, "s"));
    assertThatIllegalStateException().isThrownBy(() -> recording.failUpload("E", null));
    assertThatIllegalStateException().isThrownBy(() -> recording.beginTranscription());
    assertThatIllegalStateException().isThrownBy(() -> recording.discard());
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.DISCARDED);
  }
}
