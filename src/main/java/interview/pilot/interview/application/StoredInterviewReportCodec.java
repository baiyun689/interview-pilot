package interview.pilot.interview.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.InterviewReport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class StoredInterviewReportCodec {
  private final ObjectMapper objectMapper;

  public StoredInterviewReportCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String write(UUID sessionId, InterviewReport report) {
    try {
      return objectMapper.writeValueAsString(new Snapshot(sessionId, report));
    } catch (JacksonException exception) {
      throw invalid();
    }
  }

  public InterviewReport read(String snapshot, UUID expectedSessionId) {
    if (snapshot == null || snapshot.isBlank()) throw invalid();
    try {
      Snapshot stored = objectMapper.readValue(snapshot, Snapshot.class);
      if (stored == null || !expectedSessionId.equals(stored.sessionId())
          || stored.report() == null) {
        throw invalid();
      }
      return stored.report();
    } catch (JacksonException | IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private IllegalStateException invalid() {
    return new IllegalStateException("Stored interview report is invalid");
  }

  private record Snapshot(UUID sessionId, InterviewReport report) {}
}
