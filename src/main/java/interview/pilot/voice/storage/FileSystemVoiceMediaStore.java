package interview.pilot.voice.storage;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import interview.pilot.voice.domain.ProbedAudio;
import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaNotFoundException;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;
import interview.pilot.voice.infrastructure.AudioProbe;

/**
 * File-system backed {@link VoiceMediaStore}, rooted at {@code app.voice.files-root} (a
 * Docker volume in production).
 *
 * <p>Storage keys are immutable (plan §9) and never contain user-supplied names. The
 * security model reuses the validated approach of {@code FileSystemKnowledgeDocumentStore} as
 * a standalone implementation: strict storage-key pattern, per-component symlink and private
 * POSIX permission checks, single-hard-link files, and writes staged in a temporary file in
 * the target directory followed by an atomic rename so readers never observe partial content.
 *
 * <p>Rewrite semantics: because keys are immutable by convention, a reused key means the
 * caller made a mistake; the final rename nonetheless atomically replaces the previous file
 * (REPLACE_EXISTING), which is what makes the delete-then-replay recovery path (plan §9)
 * safe while guaranteeing readers never see torn content.
 *
 * <p>Writes probe the staged temporary file before the rename; deterministic rejections
 * ({@link VoiceMediaUnsupportedException}, {@link VoiceMediaTooLargeException}) delete the
 * temporary file and leave nothing visible at the key.
 */
public final class FileSystemVoiceMediaStore implements VoiceMediaStore {

  private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> PRIVATE_FILE =
      PosixFilePermissions.fromString("rw-------");

  private final Path configuredRoot;
  private final Path root;
  private final boolean posix;
  private final boolean unix;
  private final AtomicMover mover;
  private final AudioProbe probe;
  private final ReentrantLock lock = new ReentrantLock();

  public FileSystemVoiceMediaStore(Path root, AudioProbe probe) {
    this(root, probe, FileSystemVoiceMediaStore::atomicMove);
  }

  FileSystemVoiceMediaStore(Path root, AudioProbe probe, AtomicMover mover) {
    this.configuredRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    this.probe = Objects.requireNonNull(probe, "probe");
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
      throw storageFailure("Unable to initialize the voice files root", exception);
    }
  }

  @Override
  public StoredVoiceMedia store(VoiceMediaKey key, InputStream source, long maxBytes) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(source, "source");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    String storageKey = key.storageKey();
    lock.lock();
    try {
      Path target = resolve(storageKey);
      createSafeDirectories(target.getParent());
      verifyPrivatePath(target.getParent());
      Path temporary = Files.createTempFile(target.getParent(), ".media-", ".tmp");
      try {
        setPrivateFilePermissions(temporary);
        HashAndSize written;
        try (InputStream input = source) {
          written = copyBounded(input, temporary, maxBytes);
        }
        ProbedAudio probed = probe.probe(temporary);
        verifyPrivateRoot();
        verifyPrivatePath(target.getParent());
        rejectSymbolicLink(target);
        mover.move(temporary, target);
        verifyPrivateRoot();
        requireRegularFile(target);
        setPrivateFilePermissions(target);
        return new StoredVoiceMedia(
            storageKey, written.sha256(), written.sizeBytes(), probed.mediaType(), probed.duration());
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw storageFailure("Unable to store voice media", exception);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public StoredVoiceMedia store(
      VoiceMediaKey key, Path stagedFile, long maxBytes, ProbedAudio probed) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(stagedFile, "stagedFile");
    Objects.requireNonNull(probed, "probed");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    String storageKey = key.storageKey();
    lock.lock();
    try {
      Path target = resolve(storageKey);
      createSafeDirectories(target.getParent());
      verifyPrivatePath(target.getParent());
      rejectSymbolicLink(stagedFile);
      if (!Files.isRegularFile(stagedFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("The staged voice media file is not a regular file");
      }
      long sizeBytes = Files.size(stagedFile);
      if (sizeBytes > maxBytes) {
        throw new VoiceMediaTooLargeException(maxBytes);
      }
      // No probing here: the caller verified the media in phase 2 (plan §9 keeps the probe
      // outside the transaction); the staged file is hashed in one pass and moved in place.
      String sha256 = hash(stagedFile);
      verifyPrivateRoot();
      verifyPrivatePath(target.getParent());
      rejectSymbolicLink(target);
      mover.move(stagedFile, target); // consumes the staged file
      verifyPrivateRoot();
      requireRegularFile(target);
      setPrivateFilePermissions(target);
      return new StoredVoiceMedia(
          storageKey, sha256, sizeBytes, probed.mediaType(), probed.duration());
    } catch (IOException exception) {
      throw storageFailure("Unable to store voice media", exception);
    } finally {
      lock.unlock();
    }
  }

  private static String hash(Path file) throws IOException {
    MessageDigest digest = sha256Digest();
    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[8192];
      for (int read; (read = input.read(buffer)) >= 0;) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  @Override
  public VoiceMediaResource open(String storageKey) {
    lock.lock();
    try {
      Path target = resolve(storageKey);
      requireRegularFile(target);
      InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
      try {
        verifyPrivateRoot();
        requireRegularFile(target);
        return new VoiceMediaResource(input, Files.size(target), null, target);
      } catch (RuntimeException exception) {
        input.close();
        throw exception;
      }
    } catch (IOException exception) {
      throw storageFailure("Unable to open voice media", exception);
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
      throw storageFailure("Unable to delete voice media", exception);
    } finally {
      lock.unlock();
    }
  }

  private Path resolve(String storageKey) {
    VoiceStorageKeys.requireValid(storageKey);
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
        if (Files.isSymbolicLink(current)
            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
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
      throw storageFailure("Voice files root is unsafe", exception);
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
        throw storageFailure("Voice media path is unsafe", exception);
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

  /**
   * Security checks run first: only after they all pass is genuine absence reported as
   * {@link VoiceMediaNotFoundException} so cleanup can treat it as "already gone" without
   * fail-open on symlinks, directories or multi-linked files (plan §14).
   */
  private void requireRegularFile(Path target) {
    verifyPrivateRoot();
    verifyPrivatePath(target.getParent());
    if (Files.isSymbolicLink(target)) {
      throw invalidStorageKey();
    }
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new VoiceMediaNotFoundException();
    }
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw invalidStorageKey();
    }
    if (unix && linkCount(target) != 1) {
      throw new IllegalArgumentException("Voice media must not have multiple links");
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
      throw storageFailure("Voice media link count could not be verified", exception);
    }
  }

  private static HashAndSize copyBounded(InputStream input, Path target, long maxBytes)
      throws IOException {
    long copied = 0;
    byte[] buffer = new byte[8192];
    MessageDigest digest = sha256Digest();
    try (var output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      for (int read; (read = input.read(buffer)) >= 0;) {
        if (copied + read > maxBytes) {
          throw new VoiceMediaTooLargeException(maxBytes);
        }
        output.write(buffer, 0, read);
        digest.update(buffer, 0, read);
        copied += read;
      }
    }
    return new HashAndSize(HexFormat.of().formatHex(digest.digest()), copied);
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
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
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static IllegalArgumentException invalidStorageKey() {
    return new IllegalArgumentException("Voice media storage key is invalid");
  }

  private static VoiceMediaStorageException storageFailure(String message, Exception cause) {
    return new VoiceMediaStorageException(message, cause);
  }

  @FunctionalInterface
  interface AtomicMover {
    void move(Path source, Path target) throws IOException;
  }

  private record HashAndSize(String sha256, long sizeBytes) {}
}
