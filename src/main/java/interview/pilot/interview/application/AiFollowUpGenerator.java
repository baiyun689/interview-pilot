package interview.pilot.interview.application;

import java.util.LinkedHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.rag.RagContextSnapshot;

@Component
public class AiFollowUpGenerator implements FollowUpGenerator {
  private final StructuredOutputInvoker output;
  private final PromptJsonEncoder json;
  private final String systemPrompt;
  private final String userPrompt;

  public AiFollowUpGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/follow-up-system.st") Resource system,
      @Value("classpath:prompts/follow-up-user.st") Resource user) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(system);
    this.userPrompt = PromptResourceReader.read(user);
  }

  @Override
  public String generate(
      String providerId, String modelName, String parentQuestion, String latestAnswer,
      Object focusPoints, RagContextSnapshot rag, InterviewPhase phase, Difficulty difficulty) {
    var context = new LinkedHashMap<String, Object>();
    context.put("parentQuestion", parentQuestion);
    context.put("latestAnswer", latestAnswer);
    context.put("focusPoints", focusPoints);
    context.put("rag", rag);
    context.put("phase", phase);
    context.put("difficulty", difficulty);
    FollowUpOutput result = output.invoke(new AiRequest(
        providerId, modelName, systemPrompt,
        userPrompt.replace("{{CONTEXT_JSON}}", json.encode(context)), FollowUpOutput.class),
        FollowUpOutput.class);
    String question = result == null || result.question() == null ? "" : result.question().trim();
    if (question.length() < 20 || question.length() > 160) {
      throw new InvalidQuestionDeckException("follow-up question length is invalid");
    }
    return question;
  }

  public record FollowUpOutput(String question) { }
}
