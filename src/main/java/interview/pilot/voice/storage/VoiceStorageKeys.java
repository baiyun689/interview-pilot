package interview.pilot.voice.storage;

import java.util.UUID;
import java.util.regex.Pattern;

import interview.pilot.voice.domain.VoiceMediaKind;

/**
 * Shared storage-key validation for both media store adapters (plan §9). Keys are composed
 * exclusively of server-generated identifiers and match exactly one layout, so traversal,
 * absolute and encoded variants are rejected by construction.
 *
 * <p>{@link #parse} additionally exposes the key components to the cleanup sweeper (Task 11):
 * the orphan-file sweep derives the owning row lookup (kind + resource id) from a media
 * directory file's relative path, so the walk never guesses a key layout of its own.
 */
public final class VoiceStorageKeys {

  private static final Pattern STORAGE_KEY = Pattern.compile(
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/"
          + "[0-9]+/(recordings|speech)/"
          + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(source|audio)");

  private VoiceStorageKeys() {}

  static void requireValid(String storageKey) {
    if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
      throw new IllegalArgumentException("Voice media storage key is invalid");
    }
  }

  /**
   * Splits a storage key (or a media-directory file's relative path, normalized to '/') into
   * its components, or returns {@code null} when the layout does not match a media key. The
   * file-name component is implied by the kind ({@code source} vs {@code audio}).
   */
  public static Parsed parse(String storageKey) {
    if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
      return null;
    }
    String[] parts = storageKey.split("/");
    try {
      return new Parsed(
          UUID.fromString(parts[0]),
          Long.parseLong(parts[1]),
          "recordings".equals(parts[2]) ? VoiceMediaKind.RECORDING : VoiceMediaKind.SPEECH,
          UUID.fromString(parts[3]));
    } catch (RuntimeException exception) {
      return null; // the pattern is strict; a parse hiccup means "not a media key"
    }
  }

  public record Parsed(UUID userId, long sessionId, VoiceMediaKind kind, UUID resourceId) {}
}
