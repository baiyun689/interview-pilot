-- V22: the transcription listener persists the ASR round-trip duration (plan §10 step 4).
-- duration_millis on voice_recording stays the probed MEDIA duration; the listener's
-- transcription elapsed time is a separate diagnostic column.
ALTER TABLE voice_recording
  ADD COLUMN asr_duration_millis BIGINT NULL AFTER provider_request_id;
