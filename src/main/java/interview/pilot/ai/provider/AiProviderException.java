package interview.pilot.ai.provider;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(code = HttpStatus.BAD_REQUEST, reason = "AI provider is unavailable")
public class AiProviderException extends IllegalArgumentException {
  public AiProviderException(String message) {
    super(message);
  }
}
