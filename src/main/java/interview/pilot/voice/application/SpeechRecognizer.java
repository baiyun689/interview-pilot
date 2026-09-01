package interview.pilot.voice.application;

import interview.pilot.voice.domain.StoredVoiceMedia;

/**
 * Speech-recognition seam (plan §5.3): adapters translate provider request/response
 * structures — the controller, listener and state machine never see them. {@code StoredVoiceMedia}
 * carries the immutable storage key and metadata; the adapter reads the content itself
 * through the media store, because this app is deployed locally with no public media URL and
 * the short-audio DashScope models accept base64 payloads.
 */
public interface SpeechRecognizer {

  Transcript transcribe(StoredVoiceMedia audio, RecognitionContext context);
}
