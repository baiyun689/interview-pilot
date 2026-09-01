package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.InputMode;

class SubmissionFingerprintTest {
  private final UUID recordingId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

  @Test
  void textSubmissionHashesTheCanonicalJsonWithNullRecordingId() {
    String expected = sha256(
        "{\"answer\":\"hello\",\"inputMode\":\"TEXT\",\"recordingId\":null}");

    assertThat(SubmissionFingerprint.of("hello", InputMode.TEXT, null)).isEqualTo(expected);
    assertThat(SubmissionFingerprint.of("hello", InputMode.TEXT, null))
        .isEqualTo(SubmissionFingerprint.of("hello", InputMode.TEXT, null));
  }

  @Test
  void voiceSubmissionHashesTheCanonicalJsonWithTheRecordingId() {
    String expected = sha256("{\"answer\":\"hello\",\"inputMode\":\"VOICE\",\"recordingId\":\""
        + recordingId + "\"}");

    assertThat(SubmissionFingerprint.of("hello", InputMode.VOICE, recordingId)).isEqualTo(expected);
  }

  @Test
  void theFingerprintChangesWithAnswerInputModeOrRecordingId() {
    String base = SubmissionFingerprint.of("hello", InputMode.VOICE, recordingId);

    assertThat(SubmissionFingerprint.of("hello edit", InputMode.VOICE, recordingId))
        .isNotEqualTo(base);
    assertThat(SubmissionFingerprint.of("hello", InputMode.TEXT, recordingId))
        .isNotEqualTo(base);
    assertThat(SubmissionFingerprint.of("hello", InputMode.VOICE,
        UUID.fromString("123e4567-e89b-12d3-a456-426614174001"))).isNotEqualTo(base);
  }

  @Test
  void quotesAndBackslashesAreEscapedCanonically() {
    String expected = sha256("{\"answer\":\"say \\\"hi\\\" \\\\ now\","
        + "\"inputMode\":\"TEXT\",\"recordingId\":null}");

    assertThat(SubmissionFingerprint.of("say \"hi\" \\ now", InputMode.TEXT, null))
        .isEqualTo(expected);
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
