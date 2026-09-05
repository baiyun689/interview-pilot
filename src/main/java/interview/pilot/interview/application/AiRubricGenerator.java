package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

@Component
public class AiRubricGenerator implements RubricGenerator {

  private final StructuredOutputInvoker output;
  private final PromptJsonEncoder json;
  private final RubricDeckAssembler assembler;
  private final String systemPrompt;
  private final String userPrompt;

  public AiRubricGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/rubric-system.st") Resource system,
      @Value("classpath:prompts/rubric-user.st") Resource user) {
    this.output = output;
    this.json = json;
    this.assembler = new RubricDeckAssembler();
    this.systemPrompt = PromptResourceReader.read(system);
    this.userPrompt = PromptResourceReader.read(user);
  }

  @Override
  public PreparedQuestionDeck generate(
      InterviewBriefSnapshot brief,
      List<QuestionSkeletonOutput.Skeleton> skeletons,
      Map<QuestionCardKey, RagContextSnapshot> snapshots) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("schemaVersion", 1);
    context.put("difficulty", brief.difficulty().name());
    context.put("questions", buildQuestions(skeletons, snapshots));

    var rubricOutput = output.invoke(new AiRequest(
        brief.providerId(),
        brief.modelName(),
        systemPrompt,
        userPrompt.replace("{{CONTEXT_JSON}}", json.encode(context)),
        RubricOutput.class), RubricOutput.class);
    return assembler.assemble(skeletons, snapshots, rubricOutput);
  }

  private List<Map<String, Object>> buildQuestions(
      List<QuestionSkeletonOutput.Skeleton> skeletons,
      Map<QuestionCardKey, RagContextSnapshot> snapshots) {
    List<Map<String, Object>> questions = new ArrayList<>();
    for (var skeleton : skeletons) {
      var key = QuestionCardKey.of(skeleton.phase(), skeleton.sequence());
      RagContextSnapshot snapshot = snapshots.get(key);
      if (snapshot == null) {
        throw new InvalidQuestionDeckException("missing snapshot before rubric generation for " + key);
      }
      Map<String, Object> question = new LinkedHashMap<>();
      question.put("phase", skeleton.phase().name());
      question.put("sequence", skeleton.sequence());
      question.put("topic", skeleton.topic());
      question.put("question", skeleton.question());
      question.put("focusPoints", skeleton.focusPoints());
      question.put("knowledgePoint", skeleton.knowledgePoint());
      question.put("retrievalKeywords", skeleton.retrievalKeywords());
      question.put("ragStatus", snapshot.status().name());
      question.put("chunks", snapshot.status() == RagStatus.RETRIEVED
          ? snapshot.chunks().stream().map(AiRubricGenerator::chunkView).toList()
          : List.of());
      questions.add(question);
    }
    return questions;
  }

  private static Map<String, Object> chunkView(RagContextSnapshot.Chunk chunk) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("pointId", chunk.pointId());
    view.put("filename", chunk.filename());
    view.put("section", chunk.section());
    view.put("pageNumber", chunk.pageNumber() == null ? "" : chunk.pageNumber());
    view.put("score", chunk.score());
    view.put("content", chunk.content());
    return view;
  }
}
