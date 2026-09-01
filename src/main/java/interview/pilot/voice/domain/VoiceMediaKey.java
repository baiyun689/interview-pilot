package interview.pilot.voice.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a voice media object.
 *
 * <p>{@link #storageKey()} derives the immutable storage key
 * {@code {userId}/{sessionId}/{kind}/{resourceId}/{file}}; only server-generated identifiers
 * appear in paths, never user-supplied file names.
 */
public record VoiceMediaKey(UUID userId, Long sessionId, VoiceMediaKind kind, UUID resourceId) {

  public VoiceMediaKey {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(resourceId, "resourceId");
  }

  public String storageKey() {
    return userId + "/" + sessionId + "/" + kind.directory() + "/" + resourceId + "/" + kind.file();
  }
}
