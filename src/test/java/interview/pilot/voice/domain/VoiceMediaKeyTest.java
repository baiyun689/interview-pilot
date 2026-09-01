package interview.pilot.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class VoiceMediaKeyTest {

  @Test
  void buildsRecordingStorageKeysWithoutAnyUserSuppliedNames() {
    UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    UUID recordingId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    var key = new VoiceMediaKey(userId, 42L, VoiceMediaKind.RECORDING, recordingId);

    assertThat(key.storageKey()).isEqualTo(
        "11111111-2222-3333-4444-555555555555/42/recordings/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/source");
  }

  @Test
  void buildsSpeechStorageKeysWithoutAnyUserSuppliedNames() {
    UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    UUID speechId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    var key = new VoiceMediaKey(userId, 7L, VoiceMediaKind.SPEECH, speechId);

    assertThat(key.storageKey()).isEqualTo(
        "11111111-2222-3333-4444-555555555555/7/speech/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/audio");
  }

  @Test
  void rejectsKeysWithoutAUserId() {
    assertThatNullPointerException().isThrownBy(() ->
        new VoiceMediaKey(null, 1L, VoiceMediaKind.RECORDING, UUID.randomUUID()));
  }

  @Test
  void rejectsKeysWithoutASessionId() {
    assertThatNullPointerException().isThrownBy(() ->
        new VoiceMediaKey(UUID.randomUUID(), null, VoiceMediaKind.RECORDING, UUID.randomUUID()));
  }

  @Test
  void rejectsKeysWithoutAKind() {
    assertThatNullPointerException().isThrownBy(() ->
        new VoiceMediaKey(UUID.randomUUID(), 1L, null, UUID.randomUUID()));
  }

  @Test
  void rejectsKeysWithoutAResourceId() {
    assertThatNullPointerException().isThrownBy(() ->
        new VoiceMediaKey(UUID.randomUUID(), 1L, VoiceMediaKind.RECORDING, null));
  }
}
