package interview.pilot.knowledge.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

  private static MockMultipartFile multipart(String filename, String content) {
    return new MockMultipartFile("file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8));
  }
}
