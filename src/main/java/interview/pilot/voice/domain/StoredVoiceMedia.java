package interview.pilot.voice.domain;

import java.time.Duration;

/**
 * Metadata of a stored voice media object: the immutable storage key, the SHA-256 of the
 * content, its byte size, and the probed media type and duration when known (the in-memory
 * test double does not probe and returns null for both).
 */
public record StoredVoiceMedia(
    String storageKey, String sha256, long sizeBytes, String mediaType, Duration duration) {}
