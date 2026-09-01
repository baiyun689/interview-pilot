package interview.pilot.voice.application;

import java.util.UUID;

/**
 * Read view of a turn's question speech (plan §8.5). {@code speechId} and {@code mediaUrl}
 * are null for the view-only NOT_AVAILABLE status; {@code mediaUrl} is only populated in READY
 * state; {@code retryable} mirrors status == FAILED; {@code safeError} is only surfaced while
 * FAILED (a retried speech is PENDING again and its past-generation error is no longer
 * current). The media URL is relative — the client resolves it against its API base.
 */
public record QuestionSpeechView(
    UUID speechId,
    QuestionSpeechViewStatus status,
    String mediaUrl,
    boolean retryable,
    String safeError) {}
