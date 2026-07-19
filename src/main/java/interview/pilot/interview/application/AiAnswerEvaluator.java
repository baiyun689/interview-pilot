package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.AnswerEvaluation;

@Component
public class AiAnswerEvaluator implements AnswerEvaluator {
  private final StructuredOutputInvoker output;
  private final String systemPrompt;
  private final String userPrompt;
  private final PromptJsonEncoder json;

  public AiAnswerEvaluator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/answer-evaluation-system.st") Resource systemPrompt,
      @Value("classpath:prompts/answer-evaluation-user.st") Resource userPrompt) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.userPrompt = PromptResourceReader.read(userPrompt);
  }

  @Override
  public AnswerEvaluation evaluate(AnswerEvaluationRequest request) {
    var rag = request.ragContext() != null ? request.ragContext()
        : interview.pilot.interview.rag.RagContextSnapshot.notConfigured();
    var data = json.encode(java.util.Map.of(
        "request", request,
        "ragContext", rag.status() == interview.pilot.interview.rag.RagStatus.RETRIEVED
            ? rag : java.util.Map.of("status", rag.status().name())));
    return output.invoke(new AiRequest(
        request.providerId(), request.modelName(), systemPrompt,
        userPrompt + "\n<untrusted_context_json>\n" + data + "\n</untrusted_context_json>",
        AnswerEvaluation.class), AnswerEvaluation.class);
  }
}
