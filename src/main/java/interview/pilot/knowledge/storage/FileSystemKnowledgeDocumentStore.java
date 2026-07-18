package interview.pilot.knowledge.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.web.multipart.MultipartFile;

public final class FileSystemKnowledgeDocumentStore implements KnowledgeDocumentStore {
  private static final Pattern STORAGE_KEY = Pattern.compile(
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/"
          + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/source");

  private final Path root;

  public FileSystemKnowledgeDocumentStore(Path root) {
    Objects.requireNonNull(root, "root");
    try {
      Path configuredRoot = root.toAbsolutePath().normalize();
      Files.createDirectories(configuredRoot);
      if (Files.isSymbolicLink(configuredRoot)) {
        throw new IllegalArgumentException("Knowledge files root must not be a symbolic link");
      }
      this.root = configuredRoot.toRealPath();
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to initialize the knowledge files root", exception);
    }
  }

  @Override
  public String store(UUID userId, UUID documentId, MultipartFile file) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(file, "file");

    String storageKey = userId + "/" + documentId + "/source";
    Path target = resolve(storageKey);
    try {
      createSafeDirectories(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), ".source-", ".tmp");
      try {
        try (InputStream input = file.getInputStream()) {
          Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        moveIntoPlace(temporary, target);
      } finally {
        Files.deleteIfExists(temporary);
      }
      return storageKey;
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to store knowledge document", exception);
    }
  }

  @Override
  public InputStream open(String storageKey) {
    Path target = resolve(storageKey);
    try {
      requireRegularFile(target);
      return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to open knowledge document", exception);
    }
  }

  @Override
  public void delete(String storageKey) {
    Path target = resolve(storageKey);
    try {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
        throw invalidStorageKey();
      }
      Files.deleteIfExists(target);
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to delete knowledge document", exception);
    }
  }

  private Path resolve(String storageKey) {
    if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
      throw invalidStorageKey();
    }
    Path target = root.resolve(storageKey).normalize();
    if (!target.startsWith(root)) {
      throw invalidStorageKey();
    }
    assertNoSymbolicLinks(target.getParent());
    return target;
  }

  private void createSafeDirectories(Path targetDirectory) throws IOException {
    Path relative = root.relativize(targetDirectory);
    Path current = root;
    for (Path component : relative) {
      current = current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw invalidStorageKey();
        }
      } else {
        Files.createDirectory(current);
      }
    }
  }

  private void assertNoSymbolicLinks(Path directory) {
    Path relative = root.relativize(directory);
    Path current = root;
    for (Path component : relative) {
      current = current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
        throw invalidStorageKey();
      }
    }
  }

  private void requireRegularFile(Path target) {
    if (Files.isSymbolicLink(target)
        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Knowledge document does not exist");
    }
  }

  private static void moveIntoPlace(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static IllegalArgumentException invalidStorageKey() {
    return new IllegalArgumentException("Knowledge storage key is invalid");
  }
}
