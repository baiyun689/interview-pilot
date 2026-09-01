package interview.pilot.interview.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import interview.pilot.interview.domain.InputMode;

/**
 * SHA-256 of the canonical submission JSON {@code {"answer","inputMode","recordingId"}} (plan
 * §8.4). Same requestId + different answer, input mode, or recording all change the
 * fingerprint, so replays of a consumed requestId are stable REQUEST_ID_CONFLICTs. The
 * canonical form escapes only {@code \} and {@code "} — enough for a deterministic digest,
 * not a JSON validator.
 */
final class SubmissionFingerprint {
  private SubmissionFingerprint() {}

  static String of(String answer, InputMode inputMode, UUID recordingId) {
    return sha256(normalized(answer, inputMode, recordingId));
  }

  private static String normalized(String answer, InputMode inputMode, UUID recordingId) {
    return "{\"answer\":" + jsonString(answer) + ",\"inputMode\":\"" + inputMode.name()
        + "\",\"recordingId\":" + (recordingId == null ? "null" : "\"" + recordingId + "\"") + "}";
  }

  private static String jsonString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  static String sha256(String normalizedSubmission) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(normalizedSubmission.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }
}
