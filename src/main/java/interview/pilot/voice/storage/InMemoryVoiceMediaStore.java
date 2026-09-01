package interview.pilot.voice.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import interview.pilot.voice.domain.StoredVoiceMedia;
import interview.pilot.voice.domain.VoiceMediaKey;
import interview.pilot.voice.domain.VoiceMediaResource;
import interview.pilot.voice.domain.VoiceMediaStorageException;
import interview.pilot.voice.domain.VoiceMediaTooLargeException;

/**
 * In-memory {@link VoiceMediaStore} used by tests. Mirrors the file-system adapter's key
 * validation, size limit and atomic-replace rewrite semantics, but does not probe media:
 * {@link StoredVoiceMedia#mediaType()} and duration are always null. Media validation
 * behavior is exercised against {@link FileSystemVoiceMediaStore}.
 */
public final class InMemoryVoiceMediaStore implements VoiceMediaStore {

  private final Map<String, byte[]> media = new ConcurrentHashMap<>();

  @Override
  public StoredVoiceMedia store(VoiceMediaKey key, InputStream source, long maxBytes) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(source, "source");
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    String storageKey = key.storageKey();
    VoiceStorageKeys.requireValid(storageKey);
    byte[] bytes;
    try (InputStream input = source) {
      bytes = copyBounded(input, maxBytes);
    } catch (IOException exception) {
      throw new VoiceMediaStorageException("Unable to store voice media", exception);
    }
    media.put(storageKey, bytes);
    return new StoredVoiceMedia(storageKey, sha256(bytes), bytes.length, null, null);
  }

  @Override
  public VoiceMediaResource open(String storageKey) {
    VoiceStorageKeys.requireValid(storageKey);
    byte[] bytes = media.get(storageKey);
    if (bytes == null) {
      throw new IllegalArgumentException("Voice media does not exist");
    }
    return new VoiceMediaResource(new ByteArrayInputStream(bytes), bytes.length, null, null);
  }

  @Override
  public void delete(String storageKey) {
    VoiceStorageKeys.requireValid(storageKey);
    if (media.remove(storageKey) == null) {
      throw new IllegalArgumentException("Voice media does not exist");
    }
  }

  private static byte[] copyBounded(InputStream input, long maxBytes) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long copied = 0;
    for (int read; (read = input.read(buffer)) >= 0;) {
      if (copied + read > maxBytes) {
        throw new VoiceMediaTooLargeException(maxBytes);
      }
      output.write(buffer, 0, read);
      copied += read;
    }
    return output.toByteArray();
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
