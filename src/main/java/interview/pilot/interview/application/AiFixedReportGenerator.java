package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.FixedInterviewReport;

@Component
public class AiFixedReportGenerator implements FixedReportGenerator {
  private final StructuredOutputInvoker output;
  private final PromptJsonEncoder json;
  private final String systemPrompt;
  private final String userPrompt;

  public AiFixedReportGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/fixed-interview-report-system.st") Resource system,
      @Value("classpath:prompts/fixed-interview-report-user.st") Resource user) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(system);
    this.userPrompt = PromptResourceReader.read(user);
  }

  @Override
  public FixedInterviewReport generate(
      String providerId, String modelName, FixedReportInput input) {
    return output.invoke(new AiRequest(
        providerId, modelName, systemPrompt,
        userPrompt.replace("{{CONTEXT_JSON}}", json.encode(input)),
        FixedInterviewReport.class), FixedInterviewReport.class);
  }
}
