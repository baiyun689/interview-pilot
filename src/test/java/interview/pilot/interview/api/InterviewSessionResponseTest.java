package interview.pilot.interview.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.SessionStatus;
import tools.jackson.databind.ObjectMapper;

class InterviewSessionResponseTest {
  @Test
  void carriesTheSessionInterviewMode() {
    var response = new InterviewSessionResponse(
        UUID.randomUUID(), 7L, "Java 后端", "JD", SessionStatus.INTERVIEWING,
        Difficulty.MEDIUM, InterviewSize.STANDARD, InterviewMode.VOICE,
        JobSourceType.CUSTOM, 3, 2, 9, "dashscope", "qwen", null, null, List.of(), false, null, 0);

    assertThat(response.interviewMode()).isEqualTo(InterviewMode.VOICE);
  }

  @Test
  void jsonSerializationExposesInterviewModeForTheFrontend() throws Exception {
    var response = new InterviewSessionResponse(
        UUID.randomUUID(), 7L, "Java 后端", "JD", SessionStatus.INTERVIEWING,
        Difficulty.MEDIUM, InterviewSize.STANDARD, InterviewMode.VOICE,
        JobSourceType.CUSTOM, 3, 2, 9, "dashscope", "qwen", null, null, List.of(), false, null, 0);

    String json = new ObjectMapper().writeValueAsString(response);

    assertThat(json).contains("\"interviewMode\":\"VOICE\"");
  }
}
