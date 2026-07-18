package interview.pilot.ai;

import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.model.AiResponse;

public interface AiGateway {
  AiResponse generate(AiRequest request);
}
