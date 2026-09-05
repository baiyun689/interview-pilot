package interview.pilot.interview.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.RubricPoint;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

/**
 * LLM-backed {@link AnswerEvaluator}: builds a compact prompt from the frozen rubric and this
 * question's own reference, asks for covered/missing/incorrect points, then delegates every
 * trust decision (grounding, citation legality, score clamping) to
 * {@link AnswerEvaluationAssembler}.
 */
@Component
public class AiAnswerEvaluator implements AnswerEvaluator {

  private final StructuredOutputInvoker output;
  private final PromptJsonEncoder json;
  private final AnswerEvaluationAssembler assembler;
  private final String systemPrompt;
  private final String userPrompt;

  public AiAnswerEvaluator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      AnswerEvaluationAssembler assembler,
      @Value("classpath:prompts/answer-evaluation-system.st") Resource system,
      @Value("classpath:prompts/answer-evaluation-user.st") Resource user) {
    this.output = output;
    this.json = json;
    this.assembler = assembler;
    this.systemPrompt = PromptResourceReader.read(system);
    this.userPrompt = PromptResourceReader.read(user);
  }

  @Override
  public interview.pilot.interview.domain.AnswerEvaluation evaluate(AnswerEvaluationInput input) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("phase", input.phase().name());
    context.put("difficulty", input.difficulty().name());
    context.put("question", input.question());
    context.put("answer", input.answer());
    context.put("cardGroundingMode", input.cardGroundingMode().name());
    context.put("rubric", input.rubric().stream().map(AiAnswerEvaluator::rubricView).toList());
    RagContextSnapshot snapshot = input.snapshot();
    context.put("ragStatus", snapshot.status().name());
    context.put("referenceChunks", snapshot.status() == RagStatus.RETRIEVED
        ? snapshot.chunks().stream().map(AiAnswerEvaluator::chunkView).toList()
        : List.of());

    var modelOutput = output.invoke(new AiRequest(
        input.providerId(),
        input.modelName(),
        systemPrompt,
        userPrompt.replace("{{CONTEXT_JSON}}", json.encode(context)),
        AnswerEvaluationOutput.class), AnswerEvaluationOutput.class);
    return assembler.assemble(modelOutput, input, Instant.now());
  }

  private static Map<String, Object> rubricView(RubricPoint point) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("keyPoint", point.keyPoint());
    view.put("acceptanceHint", point.acceptanceHint());
    view.put("sourcePointId", point.grounded() ? point.sourcePointId() : null);
    return view;
  }

  private static Map<String, Object> chunkView(RagContextSnapshot.Chunk chunk) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("pointId", chunk.pointId());
    view.put("filename", chunk.filename());
    view.put("section", chunk.section());
    view.put("pageNumber", chunk.pageNumber() == null ? "" : chunk.pageNumber());
    view.put("content", chunk.content());
    return view;
  }
}
