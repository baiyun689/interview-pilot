package interview.pilot.voice.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaKind;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;
import interview.pilot.voice.domain.VoiceMediaUnsupportedException;
import interview.pilot.voice.infrastructure.AudioProbe;

class FileSystemVoiceMediaStoreTest {
  private static final long MAX = 1024;
  private final AudioProbe probe = file -> new ProbedAudio("audio/webm", Duration.ofSeconds(10));

  @TempDir
  Path root;

  @Test
  void storesOpensAndDeletesMediaByImmutableStorageKey() throws Exception {
    VoiceMediaKey key = recordingKey();
    var store = new FileSystemVoiceMediaStore(root, probe);

    var stored = store.store(key, stream("media bytes"), MAX);

    assertThat(stored.storageKey()).isEqualTo(key.storageKey());
    assertThat(stored.sha256()).isEqualTo(sha256("media bytes"));
    assertThat(stored.sizeBytes()).isEqualTo(11);
    assertThat(stored.mediaType()).isEqualTo("audio/webm");
    assertThat(stored.duration()).isEqualTo(Duration.ofSeconds(10));
    try (VoiceMediaResource resource = store.open(key.storageKey())) {
      assertThat(resource.contentLength()).isEqualTo(11);
      assertThat(resource.path()).isEqualTo(root.resolve(key.storageKey()));
      assertThat(new String(resource.inputStream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("media bytes");
    }

    store.delete(key.storageKey());
    assertThat(root.resolve(key.storageKey())).doesNotExist();
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key.storageKey()));
  }

  @Test
  void storesSpeechMediaUnderTheSpeechKeyLayout() {
    VoiceMediaKey key =
        new VoiceMediaKey(UUID.randomUUID(), 7L, VoiceMediaKind.SPEECH, UUID.randomUUID());
    var store = new FileSystemVoiceMediaStore(root, probe);

    var stored = store.store(key, stream("speech audio"), MAX);

    assertThat(stored.storageKey()).isEqualTo(key.storageKey());
    assertThat(key.storageKey()).contains("/speech/").endsWith("/audio");
    assertThat(root.resolve(key.storageKey())).isRegularFile();
  }

  @Test
  void rejectsOversizedContentWithoutWritingTheOverLimitChunkOrLeavingFiles() throws Exception {
    VoiceMediaKey key = recordingKey();
    var store = new FileSystemVoiceMediaStore(root, probe);
    CountingInputStream source = new CountingInputStream(new byte[100_000]);

    assertThatThrownBy(() -> store.store(key, source, 1024))
        .isInstanceOf(VoiceMediaTooLargeException.class);

    assertThat(source.readBytes()).isLessThan(100_000);
    assertThat(root.resolve(key.storageKey())).doesNotExist();
    try (var files = Files.list(root.resolve(key.storageKey()).getParent())) {
      assertThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".media-"))).isTrue();
    }
  }

  @Test
  void failsClosedWhenTheFinalMoveFailsAndPreservesExistingContent() throws Exception {
    VoiceMediaKey key = recordingKey();
    var workingStore = new FileSystemVoiceMediaStore(root, probe);
    workingStore.store(key, stream("old content"), MAX);
    var store = new FileSystemVoiceMediaStore(root, probe,
        (source, target) -> { throw new IOException("move failed"); });

    assertThatThrownBy(() -> store.store(key, stream("replacement"), MAX))
        .isInstanceOf(VoiceMediaStorageException.class);

    try (var resource = workingStore.open(key.storageKey())) {
      assertThat(new String(resource.inputStream().readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("old content");
    }
    try (var files = Files.list(root.resolve(key.storageKey()).getParent())) {
      assertThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".media-"))).isTrue();
    }
  }

  @Test
  void refusesTraversalAbsoluteAndEncodedStorageKeys() throws Exception {
    Path outside = Files.createTempFile("voice-outside", ".bin");
    Files.writeString(outside, "private");
    var store = new FileSystemVoiceMediaStore(root, probe);

    assertThatIllegalArgumentException().isThrownBy(
        () -> store.open("../" + outside.getFileName()));
    assertThatIllegalArgumentException().isThrownBy(
        () -> store.open("..%2F..%2F" + outside.getFileName()));
    assertThatIllegalArgumentException().isThrownBy(
        () -> store.open("C:\\Windows\\system.ini"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open("/etc/passwd"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete("not-a-storage-key"));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(null));

    assertThat(Files.readString(outside)).isEqualTo("private");
  }

  @Test
  void refusesAStorageDirectorySymlinkThatPointsOutsideThePrivateRoot() throws Exception {
    UUID userId = UUID.randomUUID();
    Long sessionId = 1L;
    UUID recordingId = UUID.randomUUID();
    Path outside = Files.createTempDirectory("voice-outside");
    Path sessionDirectory = Files.createDirectories(
        root.resolve(userId.toString()).resolve(sessionId.toString()));
    try {
      Files.createSymbolicLink(sessionDirectory.resolve("recordings"), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
      Assumptions.abort("Symbolic links are not available in this test environment");
    }
    var store = new FileSystemVoiceMediaStore(root, probe);
    VoiceMediaKey key = new VoiceMediaKey(userId, sessionId, VoiceMediaKind.RECORDING, recordingId);

    assertThatIllegalArgumentException().isThrownBy(() -> store.store(key, stream("audio"), MAX));
    assertThat(outside.resolve("source")).doesNotExist();
  }

  @Test
  void rejectsATargetSymlinkAfterItsContainingDirectoryHasBeenExchanged() throws Exception {
    UUID userId = UUID.randomUUID();
    Long sessionId = 1L;
    UUID recordingId = UUID.randomUUID();
    Path recordingDirectory = Files.createDirectories(
        root.resolve(userId.toString()).resolve(sessionId.toString())
            .resolve("recordings").resolve(recordingId.toString()));
    Path outside = Files.createTempFile("voice-outside", ".bin");
    Files.writeString(outside, "secret");
    Path source = recordingDirectory.resolve("source");
    try {
      Files.createSymbolicLink(source, outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
      Assumptions.abort("Symbolic links are not available in this test environment");
    }
    var store = new FileSystemVoiceMediaStore(root, probe);
    String key = new VoiceMediaKey(userId, sessionId, VoiceMediaKind.RECORDING, recordingId)
        .storageKey();

    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete(key));
    assertThat(outside).exists();
  }

  @Test
  void rejectsAStoredFileWithAnAdditionalHardLinkWhenThePlatformExposesLinkCounts()
      throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("unix"));
    var store = new FileSystemVoiceMediaStore(root, probe);
    VoiceMediaKey key = recordingKey();
    store.store(key, stream("audio"), MAX);
    Path hardLink = root.resolve("voice-hardlink-" + UUID.randomUUID());
    try {
      Files.createLink(hardLink, root.resolve(key.storageKey()));

      assertThatIllegalArgumentException().isThrownBy(() -> store.open(key.storageKey()));
    } finally {
      Files.deleteIfExists(hardLink);
    }
  }

  @Test
  void rejectsAnExistingPosixRootThatIsWritableByItsGroup() throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(root);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx---"));
    try {
      assertThatIllegalArgumentException().isThrownBy(
          () -> new FileSystemVoiceMediaStore(root, probe));
    } finally {
      Files.setPosixFilePermissions(root, permissions);
    }
  }

  @Test
  void rejectsUnsupportedMediaAndDeletesTheTemporaryFile() throws Exception {
    VoiceMediaKey key = recordingKey();
    var store = new FileSystemVoiceMediaStore(root,
        file -> { throw new VoiceMediaUnsupportedException(); });

    assertThatThrownBy(() -> store.store(key, stream("audio"), MAX))
        .isInstanceOf(VoiceMediaUnsupportedException.class);
    assertThat(root.resolve(key.storageKey())).doesNotExist();
    try (var files = Files.list(root.resolve(key.storageKey()).getParent())) {
      assertThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".media-"))).isTrue();
    }
  }

  @Test
  void atomicallyReplacesExistingContentWhenTheSameKeyIsReused() throws Exception {
    var store = new FileSystemVoiceMediaStore(root, probe);
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
  void deleteRejectsAStorageKeyWhoseTargetIsADirectory() throws Exception {
    var store = new FileSystemVoiceMediaStore(root, probe);
    String key = recordingKey().storageKey();
    Files.createDirectories(root.resolve(key));

    assertThatIllegalArgumentException().isThrownBy(() -> store.delete(key));
    assertThat(root.resolve(key)).isDirectory();
  }

  @Test
  void rejectsInvalidArgumentsAndMissingMedia() {
    var store = new FileSystemVoiceMediaStore(root, probe);
    String key = recordingKey().storageKey();

    assertThatIllegalArgumentException().isThrownBy(
        () -> store.store(recordingKey(), stream("x"), 0));
    assertThatIllegalArgumentException().isThrownBy(
        () -> store.store(recordingKey(), stream("x"), -1));
    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete(key));
  }

  @Test
  void rejectsNullArguments() {
    assertThatThrownBy(() -> new FileSystemVoiceMediaStore(null, probe))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FileSystemVoiceMediaStore(root, null))
        .isInstanceOf(NullPointerException.class);
    var store = new FileSystemVoiceMediaStore(root, probe);
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

  /** Counts the bytes the store actually pulls from the source stream. */
  private static final class CountingInputStream extends InputStream {
    private final InputStream delegate;
    private long readBytes;

    CountingInputStream(byte[] content) {
      this.delegate = new ByteArrayInputStream(content);
    }

    @Override
    public int read() throws IOException {
      return delegate.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = delegate.read(buffer, offset, length);
      if (read > 0) {
        readBytes += read;
      }
      return read;
    }

    long readBytes() {
      return readBytes;
    }
  }
}
