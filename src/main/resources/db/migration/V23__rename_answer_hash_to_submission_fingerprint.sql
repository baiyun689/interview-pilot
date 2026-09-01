-- V23 (plan §2.2/§8.4): answer_hash becomes the submission fingerprint. Since V23 the stored
-- value is the SHA-256 of the normalized submission JSON {answer, inputMode, recordingId}
-- instead of the plain trimmed answer, so a reused requestId also detects a changed input
-- mode or a swapped recording. Old rows keep hashes of the previous form: no backfill is
-- possible (the original submissions did not carry inputMode/recordingId), and a pre-upgrade
-- in-flight attempt replayed with the same requestId surfaces REQUEST_ID_CONFLICT instead of
-- a replay. That is accepted — development databases are resettable and the unique request_id
-- remains the real idempotency guard.
ALTER TABLE answer_attempt RENAME COLUMN answer_hash TO submission_fingerprint;
