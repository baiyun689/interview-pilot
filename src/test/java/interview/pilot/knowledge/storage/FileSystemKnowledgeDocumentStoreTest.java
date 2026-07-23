package interview.pilot.knowledge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class FileSystemKnowledgeDocumentStoreTest {
  @TempDir Path root;

  @Test
  void storageKeyNeverContainsTheUserFilename() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    var store = new FileSystemKnowledgeDocumentStore(root);

    String key = store.store(userId, documentId, multipart("../../secret.md", "# JVM"));

    assertThat(key).isEqualTo(userId + "/" + documentId + "/source");
    assertThat(root.resolve(key).normalize()).startsWith(root);
    try (InputStream input = store.open(key)) {
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("# JVM");
    }
  }

  @Test
  void refusesTraversalAndNonCanonicalStorageKeys() throws Exception {
    Path outside = Files.createTempFile("knowledge-outside", ".txt");
    Files.writeString(outside, "private");
    var store = new FileSystemKnowledgeDocumentStore(root);

    assertThatIllegalArgumentException().isThrownBy(() -> store.open("../../" + outside.getFileName()));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete("not-a-storage-key"));
    assertThat(Files.readString(outside)).isEqualTo("private");
  }

  @Test
  void deleteRemovesOnlyThePrivateStoredDocument() throws Exception {
    var store = new FileSystemKnowledgeDocumentStore(root);
    String key = store.store(UUID.randomUUID(), UUID.randomUUID(), multipart("notes.txt", "Java"));

    store.delete(key);

    assertThat(root.resolve(key)).doesNotExist();
  }

  @Test
  void refusesAStorageDirectorySymlinkThatPointsOutsideThePrivateRoot() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    Path outside = Files.createTempDirectory("knowledge-outside");
    Path userDirectory = Files.createDirectories(root.resolve(userId.toString()));
    try {
      Files.createSymbolicLink(userDirectory.resolve(documentId.toString()), outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
      Assumptions.abort("Symbolic links are not available in this test environment");
    }
    var store = new FileSystemKnowledgeDocumentStore(root);

    assertThatIllegalArgumentException().isThrownBy(
        () -> store.store(userId, documentId, multipart("notes.txt", "Java")));
    assertThat(outside.resolve("source")).doesNotExist();
  }

  @Test
  void rejectsATargetSymlinkAfterItsContainingDirectoryHasBeenExchanged() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    Path documentDirectory = Files.createDirectories(root.resolve(userId.toString()).resolve(documentId.toString()));
    Path outside = Files.createTempFile("knowledge-outside", ".txt");
    Path source = documentDirectory.resolve("source");
    try {
      Files.createSymbolicLink(source, outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
      Assumptions.abort("Symbolic links are not available in this test environment");
    }
    var store = new FileSystemKnowledgeDocumentStore(root);
    String key = userId + "/" + documentId + "/source";

    assertThatIllegalArgumentException().isThrownBy(() -> store.open(key));
    assertThatIllegalArgumentException().isThrownBy(() -> store.delete(key));
    assertThat(outside).exists();
  }

  @Test
  void rejectsAnExistingPosixRootThatIsWritableByItsGroup() throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(root);
    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx---"));
    try {
      assertThatIllegalArgumentException().isThrownBy(() -> new FileSystemKnowledgeDocumentStore(root));
    } finally {
      Files.setPosixFilePermissions(root, permissions);
    }
  }

  @Test
  void rejectsOversizedContentAndPreservesTheExistingDocument() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    var store = new FileSystemKnowledgeDocumentStore(root);
    String key = store.store(userId, documentId, multipart("notes.txt", "old content"));
    var oversized = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[10 * 1024 * 1024 + 1]);

    assertThatIllegalArgumentException().isThrownBy(() -> store.store(userId, documentId, oversized));
    try (InputStream input = store.open(key)) {
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("old content");
    }
    try (var files = Files.list(root.resolve(userId.toString()).resolve(documentId.toString()))) {
      assertThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".source-"))).isTrue();
    }
  }

  @Test
  void failsClosedWhenAnAtomicMoveIsUnavailableAndPreservesExistingContent() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    var workingStore = new FileSystemKnowledgeDocumentStore(root);
    String key = workingStore.store(userId, documentId, multipart("notes.txt", "old content"));
    var store = new FileSystemKnowledgeDocumentStore(root,
        (source, target) -> { throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test"); });

    assertThatIllegalArgumentException().isThrownBy(
        () -> store.store(userId, documentId, multipart("notes.txt", "replacement")));
    try (InputStream input = workingStore.open(key)) {
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("old content");
    }
  }

  @Test
  void deleteRejectsAStorageKeyWhoseTargetIsADirectory() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    var store = new FileSystemKnowledgeDocumentStore(root);
    String key = userId + "/" + documentId + "/source";
    Files.createDirectories(root.resolve(key));

    assertThatIllegalArgumentException().isThrownBy(() -> store.delete(key));
    assertThat(root.resolve(key)).isDirectory();
  }

  @Test
  void rejectsAStoredFileWithAnAdditionalHardLinkWhenThePlatformExposesLinkCounts() throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("unix"));
    var store = new FileSystemKnowledgeDocumentStore(root);
    String key = store.store(UUID.randomUUID(), UUID.randomUUID(), multipart("notes.txt", "Java"));
    Path hardLink = Path.of(System.getProperty("java.io.tmpdir"), "knowledge-hardlink-" + UUID.randomUUID());
    try {
      Files.createLink(hardLink, root.resolve(key));

      assertThatIllegalArgumentException().isThrownBy(() -> store.open(key));
    } finally {
      Files.deleteIfExists(hardLink);
    }
  }

  private static MockMultipartFile multipart(String filename, String content) {
    return new MockMultipartFile("file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }
}
