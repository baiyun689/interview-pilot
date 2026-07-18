package interview.pilot.ai.model;

public record AiRequest(
    String providerId,
    String expectedModel,
    String systemPrompt,
    String userPrompt,
    Class<?> responseType) {

  public AiRequest(
      String providerId, String systemPrompt, String userPrompt, Class<?> responseType) {
    this(providerId, null, systemPrompt, userPrompt, responseType);
  }
}
