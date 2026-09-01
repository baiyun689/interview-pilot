package interview.pilot.interview.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.api.CreateInterviewRequest.JobSource;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import tools.jackson.databind.ObjectMapper;

class CreateInterviewRequestTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void missingInterviewModeDefaultsToText() throws Exception {
    CreateInterviewRequest request = objectMapper.readValue("""
        {"jobSource":{"type":"CUSTOM","jobTitle":"Java 后端","jobDescription":"JD"},
         "difficulty":"MEDIUM","interviewSize":"STANDARD","providerId":"dashscope",
         "knowledgeBaseIds":[]}""", CreateInterviewRequest.class);

    assertThat(request.interviewMode()).isEqualTo(InterviewMode.TEXT);
  }

  @Test
  void parsesVoiceMode() throws Exception {
    CreateInterviewRequest request = objectMapper.readValue("""
        {"jobSource":{"type":"CUSTOM","jobTitle":"Java 后端","jobDescription":"JD"},
         "difficulty":"MEDIUM","interviewSize":"STANDARD","providerId":"dashscope",
         "knowledgeBaseIds":[],"interviewMode":"VOICE"}""", CreateInterviewRequest.class);

    assertThat(request.interviewMode()).isEqualTo(InterviewMode.VOICE);
  }

  @Test
  void parsesExplicitTextMode() throws Exception {
    CreateInterviewRequest request = objectMapper.readValue("""
        {"jobSource":{"type":"CUSTOM","jobTitle":"Java 后端","jobDescription":"JD"},
         "difficulty":"MEDIUM","interviewSize":"STANDARD","providerId":"dashscope",
         "knowledgeBaseIds":[],"interviewMode":"TEXT"}""", CreateInterviewRequest.class);

    assertThat(request.interviewMode()).isEqualTo(InterviewMode.TEXT);
  }

  @Test
  void legacySixArgumentConstructorStaysText() {
    CreateInterviewRequest request = new CreateInterviewRequest(
        null, jobSource(), Difficulty.MEDIUM, InterviewSize.STANDARD, "dashscope", List.of());

    assertThat(request.interviewMode()).isEqualTo(InterviewMode.TEXT);
    assertThat(request.knowledgeBaseIds()).isEmpty();
  }

  @Test
  void explicitNullInterviewModeNormalizesToText() {
    CreateInterviewRequest request = new CreateInterviewRequest(
        null, jobSource(), Difficulty.MEDIUM, InterviewSize.STANDARD, "dashscope",
        List.of(UUID.randomUUID()), null);

    assertThat(request.interviewMode()).isEqualTo(InterviewMode.TEXT);
  }

  private JobSource jobSource() {
    return new JobSource(JobSourceType.CUSTOM, null, "Java 后端", "JD");
  }
}
