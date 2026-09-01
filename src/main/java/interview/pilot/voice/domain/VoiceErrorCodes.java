package interview.pilot.voice.domain;

/**
 * Stable voice error codes (plan §8.6). Clients react on the code, never on the message.
 * 404 hides cross-user resources, 409 signals state conflicts, 413 signals unusable upload
 * content (too large or too long), 415 signals a deterministic media rejection.
 */
public final class VoiceErrorCodes {

  public static final String VOICE_RECORDING_NOT_FOUND = "VOICE_RECORDING_NOT_FOUND";
  public static final String VOICE_UPLOAD_IN_PROGRESS = "VOICE_UPLOAD_IN_PROGRESS";
  public static final String VOICE_UPLOAD_TOO_LARGE = "VOICE_UPLOAD_TOO_LARGE";
  public static final String VOICE_MEDIA_UNSUPPORTED = "VOICE_MEDIA_UNSUPPORTED";
  public static final String VOICE_DURATION_EXCEEDED = "VOICE_DURATION_EXCEEDED";
  public static final String VOICE_TURN_NOT_CURRENT = "VOICE_TURN_NOT_CURRENT";
  public static final String VOICE_TRANSCRIPTION_IN_PROGRESS = "VOICE_TRANSCRIPTION_IN_PROGRESS";
  public static final String VOICE_TRANSCRIPTION_FAILED = "VOICE_TRANSCRIPTION_FAILED";
  public static final String VOICE_RECORDING_NOT_READY = "VOICE_RECORDING_NOT_READY";
  public static final String VOICE_RECORDING_ALREADY_ATTACHED = "VOICE_RECORDING_ALREADY_ATTACHED";

  /**
   * Question speech resources that are hidden as 404 (plan §8.6): a session of another user,
   * a turn that does not exist, a speech that belongs to a different session, or a READY row
   * whose media file is absent (crash/orphan residue — the frontend falls back to text).
   */
  public static final String QUESTION_SPEECH_NOT_FOUND = "QUESTION_SPEECH_NOT_FOUND";

  /**
   * 409 for a question speech that is not in a retryable/playable state (plan §8.6): media
   * reads of PENDING/SYNTHESIZING speech, and retries of PENDING/SYNTHESIZING/READY speech
   * (including a retry racing an ACTIVE listener claim). One stable code covers "not ready"
   * in the same way VOICE_RECORDING_NOT_READY does for recordings.
   */
  public static final String QUESTION_SPEECH_NOT_READY = "QUESTION_SPEECH_NOT_READY";

  /**
   * 409 for media reads of a FAILED speech (plan §8.6): the synthesis deterministically
   * failed or exhausted its retries — the client retries the speech (QUESTION_SPEECH_NOT_READY
   * applies while the retried synthesis is in flight) or falls back to text.
   */
  public static final String QUESTION_SPEECH_FAILED = "QUESTION_SPEECH_FAILED";

  /**
   * safe_error on FAILED question_speech rows: a deterministic synthesis failure (provider
   * rejection, empty/unsupported audio, text drift) or retry exhaustion. Speech is a
   * degradable playback capability — a FAILED speech never touches the turn or the session,
   * and the question text remains fully answerable (plan §11).
   */
  public static final String VOICE_QUESTION_SPEECH_FAILED = "VOICE_QUESTION_SPEECH_FAILED";

  /**
   * Voice and text submission fields mixed inconsistently (plan §8.4): a recordingId with
   * inputMode TEXT, inputMode VOICE without a recordingId, or a recordingId on a TEXT-mode
   * session. All are client bugs — the correct client never sends them — so the stable 409
   * lets the frontend treat the request as unrecoverable.
   */
  public static final String VOICE_INPUT_MODE_MISMATCH = "VOICE_INPUT_MODE_MISMATCH";

  /**
   * Diagnostic safe_error stored on FAILED recordings when the media probe failed
   * operationally (ffprobe start/timeout/IO). Not a stable HTTP code: the wire response for
   * operational probe failures stays a generic 500 (plan §14 keeps provider details off the
   * wire); the recording row keeps the code for diagnosis and GET.
   */
  public static final String VOICE_MEDIA_PROBE_FAILED = "VOICE_MEDIA_PROBE_FAILED";

  /**
   * Diagnostic safe_error stored on FAILED recordings when the media store failed
   * operationally (temp staging or install IO). Same off-wire treatment as
   * {@link #VOICE_MEDIA_PROBE_FAILED}; the row is FAILED so the requestId never looks like a
   * 10-minute RECEIVING upload.
   */
  public static final String VOICE_MEDIA_STORAGE_FAILED = "VOICE_MEDIA_STORAGE_FAILED";

  private VoiceErrorCodes() {}
}
