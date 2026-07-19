package interview.pilot.knowledge.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.springframework.web.multipart.MultipartFile;

public final class FileSystemKnowledgeDocumentStore implements KnowledgeDocumentStore {
  static final long MAX_DOCUMENT_SIZE = 10L * 1024 * 1024;
  private static final Pattern STORAGE_KEY = Pattern.compile(
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/"
          + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/source");
  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE =
      PosixFilePermissions.fromString("rw-------");

  private final Path configuredRoot;
  private final Path root;
  private final boolean posix;
  private final boolean unix;
  private final AtomicMover mover;
  private final ReentrantLock lock = new ReentrantLock();

  public FileSystemKnowledgeDocumentStore(Path root) {
    this(root, FileSystemKnowledgeDocumentStore::atomicMove);
  }

  FileSystemKnowledgeDocumentStore(Path root, AtomicMover mover) {
    this.configuredRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.mover = Objects.requireNonNull(mover, "mover");
    try {
      boolean rootExisted = Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS);
      Files.createDirectories(configuredRoot);
      if (Files.isSymbolicLink(configuredRoot)) {
        throw invalidStorageKey();
      }
      this.root = configuredRoot.toRealPath();
      this.posix = Files.getFileStore(this.root).supportsFileAttributeView("posix");
      this.unix = Files.getFileStore(this.root).supportsFileAttributeView("unix");
      if (posix && !rootExisted) {
        Files.setPosixFilePermissions(this.root, PRIVATE_DIRECTORY);
      }
      verifyPrivateRoot();
    } catch (IOException exception) {
      throw storageFailure("Unable to initialize the knowledge files root", exception);
    }
  }

  @Override
  public String store(UUID userId, UUID documentId, MultipartFile file) {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(documentId, "documentId");
    Objects.requireNonNull(file, "file");
    String storageKey = userId + "/" + documentId + "/source";

    lock.lock();
    try {
      Path target = resolve(storageKey);
      createSafeDirectories(target.getParent());
      verifyPrivatePath(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), ".source-", ".tmp");
      try {
        setPrivateFilePermissions(temporary);
        try (InputStream input = file.getInputStream()) {
          copyBounded(input, temporary);
        }
        verifyPrivateRoot();
        verifyPrivatePath(target.getParent());
        rejectSymbolicLink(target);
        mover.move(temporary, target);
        verifyPrivateRoot();
        requireRegularFile(target);
        setPrivateFilePermissions(target);
      } finally {
        Files.deleteIfExists(temporary);
      }
      return storageKey;
    } catch (IOException exception) {
      throw storageFailure("Unable to store knowledge document", exception);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public InputStream open(String storageKey) {
    lock.lock();
    try {
      Path target = resolve(storageKey);
      requireRegularFile(target);
      InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
      try {
        verifyPrivateRoot();
        requireRegularFile(target);
        return input;
      } catch (RuntimeException exception) {
        input.close();
        throw exception;
      }
    } catch (IOException exception) {
      throw storageFailure("Unable to open knowledge document", exception);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void delete(String storageKey) {
    lock.lock();
    try {
      Path target = resolve(storageKey);
      requireRegularFile(target);
      verifyPrivateRoot();
      requireRegularFile(target);
      Files.delete(target);
      verifyPrivateRoot();
    } catch (IOException exception) {
      throw storageFailure("Unable to delete knowledge document", exception);
    } finally {
      lock.unlock();
    }
  }

  private Path resolve(String storageKey) {
    if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
      throw invalidStorageKey();
    }
    verifyPrivateRoot();
    Path target = root.resolve(storageKey).normalize();
    if (!target.startsWith(root)) {
      throw invalidStorageKey();
    }
    verifyPrivatePath(target.getParent());
    rejectSymbolicLink(target);
    return target;
  }

  private void createSafeDirectories(Path targetDirectory) throws IOException {
    Path current = root;
    for (Path component : root.relativize(targetDirectory)) {
      current = current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
          throw invalidStorageKey();
        }
      } else {
        Files.createDirectory(current);
        setPrivateDirectoryPermissions(current);
      }
      verifyPrivatePath(current);
    }
  }

  private void verifyPrivateRoot() {
    try {
      if (Files.isSymbolicLink(configuredRoot)
          || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)
          || !configuredRoot.toRealPath().equals(root)) {
        throw invalidStorageKey();
      }
      verifyPrivateDirectory(root);
    } catch (IOException exception) {
      throw storageFailure("Knowledge files root is unsafe", exception);
    }
  }

  private void verifyPrivatePath(Path directory) {
    Path current = root;
    for (Path component : root.relativize(directory)) {
      current = current.resolve(component);
      try {
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
          return;
        }
        if (Files.isSymbolicLink(current)
            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            || !current.toRealPath().startsWith(root)) {
          throw invalidStorageKey();
        }
        verifyPrivateDirectory(current);
      } catch (IOException exception) {
        throw storageFailure("Knowledge document path is unsafe", exception);
      }
    }
  }

  private void verifyPrivateDirectory(Path directory) throws IOException {
    if (!posix) {
      return;
    }
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory,
        LinkOption.NOFOLLOW_LINKS);
    if (permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw invalidStorageKey();
    }
  }

  private void requireRegularFile(Path target) {
    verifyPrivateRoot();
    verifyPrivatePath(target.getParent());
    if (Files.isSymbolicLink(target)
        || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Knowledge document does not exist");
    }
    if (unix && linkCount(target) != 1) {
      throw new IllegalArgumentException("Knowledge document must not have multiple links");
    }
  }

  private void rejectSymbolicLink(Path path) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
      throw invalidStorageKey();
    }
  }

  private long linkCount(Path path) {
    try {
      Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
      return ((Number) value).longValue();
    } catch (IOException | UnsupportedOperationException exception) {
      throw storageFailure("Knowledge document link count could not be verified", exception);
    }
  }

  private static void copyBounded(InputStream input, Path target) throws IOException {
    long copied = 0;
    byte[] buffer = new byte[8192];
    try (var output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      for (int read; (read = input.read(buffer)) >= 0;) {
        if (copied + read > MAX_DOCUMENT_SIZE) {
          throw new IllegalArgumentException("Knowledge document exceeds 10 MB");
        }
        output.write(buffer, 0, read);
        copied += read;
      }
    }
  }

  private void setPrivateDirectoryPermissions(Path directory) throws IOException {
    if (posix) {
      Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY);
    }
  }

  private void setPrivateFilePermissions(Path file) throws IOException {
    if (posix) {
      Files.setPosixFilePermissions(file, PRIVATE_FILE);
    }
  }

  private static void atomicMove(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      throw exception;
    }
  }

  private static IllegalArgumentException invalidStorageKey() {
    return new IllegalArgumentException("Knowledge storage key is invalid");
  }

  private static IllegalArgumentException storageFailure(String message, Exception cause) {
    return new IllegalArgumentException(message, cause);
  }

  @FunctionalInterface
  interface AtomicMover {
    void move(Path source, Path target) throws IOException;
  }
}
