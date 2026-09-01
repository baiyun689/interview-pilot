package interview.pilot.voice.application;

import java.util.UUID;

import interview.pilot.voice.domain.VoiceRecordingStatus;

/**
 * 202 response of the voice recording upload (plan §8.2). The transcription task id is the
 * id of the PENDING VOICE_TRANSCRIPTION async task created in the same transaction as the
 * UPLOADED transition; it may be null on replays of recordings that never created one.
 */
public record VoiceRecordingReceipt(
    UUID recordingId, VoiceRecordingStatus status, UUID transcriptionTaskId) {}
