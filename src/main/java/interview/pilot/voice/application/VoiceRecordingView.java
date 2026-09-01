package interview.pilot.voice.application;

import java.util.UUID;

import interview.pilot.voice.domain.VoiceRecordingStatus;

/**
 * Read view of a voice recording (plan §8.3). {@code rawTranscript} is only populated in
 * READY state — the plan exposes the raw transcript exactly when it is confirmable; every
 * other state leaves it null. {@code retryable} mirrors status == FAILED.
 */
public record VoiceRecordingView(
    UUID recordingId,
    int turnNo,
    VoiceRecordingStatus status,
    String rawTranscript,
    Long durationMillis,
    boolean retryable,
    String safeError) {}
