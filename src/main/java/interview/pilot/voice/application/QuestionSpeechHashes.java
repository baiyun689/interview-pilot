package interview.pilot.voice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of a turn's question text, shared by the creation path (question_speech.text_sha256
 * is written at row creation) and the synthesis listener (the claim and final transactions
 * re-hash the immutable turn text to guard against drift, plan §11 step 1/5).
 */
public final class QuestionSpeechHashes {

  public static String of(String questionText) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(
          digest.digest(questionText.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private QuestionSpeechHashes() {}
}
