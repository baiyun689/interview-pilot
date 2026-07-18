package interview.pilot.resume.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.resume.domain.ResumeProfile;

@Component
public class AiResumeProfiler implements ResumeProfiler {
  private final StructuredOutputInvoker structuredOutput;
  private final AiProviderService providers;
  private final String systemPrompt;
  private final String userPrompt;

  public AiResumeProfiler(
      StructuredOutputInvoker structuredOutput,
      AiProviderService providers,
      @Value("classpath:prompts/resume-profile-system.st") Resource systemPrompt,
      @Value("classpath:prompts/resume-profile-user.st") Resource userPrompt) {
    this.structuredOutput = structuredOutput;
    this.providers = providers;
    this.systemPrompt = read(systemPrompt);
    this.userPrompt = read(userPrompt);
  }

  @Override
  public ResumeProfile profile(String resumeText) {
    if (resumeText == null || resumeText.isBlank()) {
      throw new IllegalArgumentException("Resume text is required for analysis");
    }
    var request = new AiRequest(
        providers.currentDefaultProviderId(),
        systemPrompt,
        userPrompt + "\n\n<resume_text>\n" + resumeText + "\n</resume_text>",
        ResumeProfile.class);
    return structuredOutput.invoke(request, ResumeProfile.class);
  }

  private static String read(Resource resource) {
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Resume profiling prompt could not be loaded", exception);
    }
  }
}
