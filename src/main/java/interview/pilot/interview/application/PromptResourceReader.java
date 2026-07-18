package interview.pilot.interview.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;

final class PromptResourceReader {
  private PromptResourceReader() {}

  static String read(Resource resource) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Interview prompt could not be loaded", exception);
    }
  }
}
