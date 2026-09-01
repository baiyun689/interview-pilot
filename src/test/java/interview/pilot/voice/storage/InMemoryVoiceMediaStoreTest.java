package interview.pilot.voice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;

class InMemoryVoiceMediaStoreTest {
  private static final long MAX = 1024;
  private final InMemoryVoiceMediaStore store = new InMemoryVoiceMediaStore();

  @Test
  void storesOpensAndDeletesMediaByImmutableStorageKey() throws Exception {
    VoiceMediaKey key = recordingKey();

    var stored = store.store(key, stream("media bytes"), MAX);

    assertThat(stored.storageKey()).isEqualTo(key.storageKey());
    assertThat(stored.sha256()).isEqualTo(sha256("media bytes"));
    assertThat(stored.sizeBytes()).isEqualTo(11);
    assertThat(stored.mediaType()).isNull();
    assertThat(stored.duration()).isNull();
    try (var resource = store.open(key.storageKey())) {
      assertThat(resource.contentLength()).isEqualTo(11);
      assertThat(resource.path()).isNull();
      assertThat(new String(resource.inputStream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("media bytes");
    }

    store.delete(key.storageKey());
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key.storageKey()));
  }

  @Test
  void storesSpeechMediaUnderTheSpeechKeyLayout() {
    VoiceMediaKey key =
        new VoiceMediaKey(UUID.randomUUID(), 7L, VoiceMediaKind.SPEECH, UUID.randomUUID());

    var stored = store.store(key, stream("speech audio"), MAX);

    assertThat(stored.storageKey()).isEqualTo(key.storageKey());
    assertThat(key.storageKey()).contains("/speech/").endsWith("/audio");
  }

  @Test
  void rejectsOversizedContentWithoutStoringAnything() {
    VoiceMediaKey key = recordingKey();

    assertThatThrownBy(() -> store.store(key, stream(new byte[2000]), 1024))
        .isInstanceOf(VoiceMediaTooLargeException.class);
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key.storageKey()));
  }

  @Test
  void refusesTraversalAndNonCanonicalStorageKeys() {
    assertThatIllegalArgumentException().isThrownBy(() -> store.open("../../secret"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open("/etc/passwd"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open("..%2F..%2Fsecret"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete("not-a-storage-key"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(null));
  }

  @Test
  void atomicallyReplacesExistingContentWhenTheSameKeyIsReused() throws Exception {
    VoiceMediaKey key = recordingKey();
    store.store(key, stream("first"), MAX);

    var stored = store.store(key, stream("second"), MAX);

    assertThat(stored.sizeBytes()).isEqualTo(6);
    try (var resource = store.open(key.storageKey())) {
      assertThat(new String(resource.inputStream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("second");
    }
  }

  @Test
  void rejectsInvalidArgumentsAndMissingMedia() {
    String key = recordingKey().storageKey();

    assertThatIllegalArgumentException().isThrownBy(
        () -> store.store(recordingKey(), stream("x"), 0));
    assertThatIllegalArgumentException().isThrownBy(
        () -> store.store(recordingKey(), stream("x"), -1));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete(key));
    assertThatThrownBy(() -> store.store(null, stream("x"), MAX))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> store.store(recordingKey(), null, MAX))
        .isInstanceOf(NullPointerException.class);
  }

  private static VoiceMediaKey recordingKey() {
    return new VoiceMediaKey(UUID.randomUUID(), 1L, VoiceMediaKind.RECORDING, UUID.randomUUID());
  }

  private static InputStream stream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static InputStream stream(byte[] content) {
    return new ByteArrayInputStream(content);
  }

  private static String sha256(String content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
