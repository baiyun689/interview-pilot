package interview.pilot.ai;

public class AiStructuredOutputException extends RuntimeException {
  public AiStructuredOutputException(String sanitizedReason) {
    super("AI structured output failed: " + sanitizedReason);
  }
}
