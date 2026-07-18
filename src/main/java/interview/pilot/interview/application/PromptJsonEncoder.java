package interview.pilot.interview.application;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class PromptJsonEncoder {
  private final ObjectMapper objectMapper;

  public PromptJsonEncoder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Prompt context could not be encoded", exception);
    }
  }
}
