package interview.pilot.voice.application;

import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import interview.pilot.auth.application.CurrentUser;

/**
 * Recording answer seam (plan §5.1). Only the CURRENT turn of an INTERVIEWING session, in
 * ASKED or FAILED turn status, accepts recordings; {@code uploadRequestId} is the upload
 * idempotency key (V21 uq_voice_upload_request). READY only means the transcript is
 * confirmable — never that the answer is submitted; discard only applies to recordings not
 * yet bound to a final answer. Cross-user/cross-session resources surface as 404
 * VOICE_RECORDING_NOT_FOUND everywhere (plan §8.6).
 *
 * <p>Implementation hides: staged temp files, digest computation, media probing, the
 * immutable storage key, the asynchronous VOICE_TRANSCRIPTION task and the recovery of
 * races and crash residue.
 */
public interface VoiceAnswerModule {

  /**
   * Accepts an audio upload for the current turn. Same requestId + same content digest
   * replays the existing recording with its current status; same requestId + different
   * content is a stable 409 REQUEST_ID_CONFLICT; same requestId while the upload is still
   * being processed is 409 VOICE_UPLOAD_IN_PROGRESS.
   */
  VoiceRecordingReceipt accept(
      CurrentUser user, UUID sessionId, int turnNo, UUID uploadRequestId, MultipartFile audio);

  VoiceRecordingView get(CurrentUser user, UUID sessionId, UUID recordingId);

  void retry(CurrentUser user, UUID sessionId, UUID recordingId);

  void discard(CurrentUser user, UUID sessionId, UUID recordingId);
}
