package interview.pilot.interview.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.SessionStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class StoredAnswerResultCodec {
  private final ObjectMapper objectMapper;

  public StoredAnswerResultCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String write(AnswerProcessingResult result) {
    try {
      return objectMapper.writeValueAsString(result);
    } catch (JacksonException exception) {
      throw invalid();
    }
  }

  public AnswerProcessingResult readCompleted(
      String snapshot, UUID expectedSessionId, UUID expectedRequestId, int expectedTurnNo) {
    if (snapshot == null || snapshot.isBlank()) throw invalid();
    try {
      AnswerProcessingResult result = objectMapper.readValue(snapshot, AnswerProcessingResult.class);
      if (result == null
          || !expectedSessionId.equals(result.sessionId())
          || !expectedRequestId.equals(result.requestId())
          || expectedTurnNo != result.turnNo()
          || (result.sessionStatus() != SessionStatus.INTERVIEWING
              && result.sessionStatus() != SessionStatus.EVALUATING)) {
        throw invalid();
      }
      return result;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private IllegalStateException invalid() {
    return new IllegalStateException("Stored answer result is invalid");
  }
}
