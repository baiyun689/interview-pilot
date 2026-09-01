package interview.pilot.voice.storage;

import java.util.regex.Pattern;

/**
 * Shared storage-key validation for both media store adapters (plan §9). Keys are composed
 * exclusively of server-generated identifiers and match exactly one layout, so traversal,
 * absolute and encoded variants are rejected by construction.
 */
final class VoiceStorageKeys {

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
}
