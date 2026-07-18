package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.skill.SkillSnapshot;

@Component
public class AiJobProfileExtractor implements JobProfileExtractor {
  private final StructuredOutputInvoker output;
  private final String systemPrompt;
  private final String userPrompt;
  private final PromptJsonEncoder json;

  public AiJobProfileExtractor(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/job-profile-system.st") Resource systemPrompt,
      @Value("classpath:prompts/job-profile-user.st") Resource userPrompt) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.userPrompt = PromptResourceReader.read(userPrompt);
  }

  @Override
  public JobRequirements extract(String providerId, String jdText) {
    return extract(providerId, jdText, null);
  }

  @Override
  public JobRequirements extract(String providerId, String jdText, SkillSnapshot skill) {
    String normalizedJd = jdText == null ? "" : jdText.trim();
    var context = java.util.Map.of(
        "skill", skill == null ? java.util.Map.of() : skill,
        "jobDescription", normalizedJd);
    return output.invoke(new AiRequest(
        providerId,
        systemPrompt,
        userPrompt + "\n<untrusted_context_json>\n" + json.encode(context)
            + "\n</untrusted_context_json>",
        JobRequirements.class), JobRequirements.class);
  }
}
