package interview.pilot.interview.application;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.skill.SkillSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AiReportGenerator implements ReportGenerator {
  private final StructuredOutputInvoker output;
  private final ObjectMapper objectMapper;
  private final String systemPrompt;
  private final String userPrompt;

  public AiReportGenerator(
      StructuredOutputInvoker output,
      ObjectMapper objectMapper,
      @Value("classpath:prompts/interview-report-system.st") Resource systemPrompt,
      @Value("classpath:prompts/interview-report-user.st") Resource userPrompt) {
    this.output = output;
    this.objectMapper = objectMapper;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.userPrompt = PromptResourceReader.read(userPrompt);
  }

  @Override
  public InterviewReport generate(
      String providerId, String expectedModel, List<ReportEvidence> evidence) {
    return generate(providerId, expectedModel, evidence, null);
  }

  @Override
  public InterviewReport generate(
      String providerId, String expectedModel, List<ReportEvidence> evidence,
      SkillSnapshot skill) {
    try {
      String evidenceJson = objectMapper.writeValueAsString(java.util.Map.of(
          "skill", skill == null ? java.util.Map.of() : skill,
          "completedTurnEvidence", List.copyOf(evidence)));
      return output.invoke(new AiRequest(
          providerId, expectedModel, systemPrompt,
          userPrompt + "\n<untrusted_completed_turn_evidence>\n" + evidenceJson
              + "\n</untrusted_completed_turn_evidence>",
          InterviewReport.class), InterviewReport.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Completed interview evidence could not be encoded");
    }
  }
}
