package interview.pilot.interview.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;

@Component
public class AiQuestionSkeletonGenerator implements QuestionSkeletonGenerator {

  private static final InterviewPhase[] GENERATED_PHASES = {
      InterviewPhase.FUNDAMENTALS,
      InterviewPhase.PROJECT_EXPERIENCE,
      InterviewPhase.SCENARIO_TRADEOFF};

  private final StructuredOutputInvoker output;
  private final PromptJsonEncoder json;
  private final QuestionSkeletonValidator validator;
  private final String systemPrompt;
  private final String userPrompt;

  public AiQuestionSkeletonGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/question-skeleton-system.st") Resource system,
      @Value("classpath:prompts/question-skeleton-user.st") Resource user) {
    this.output = output;
    this.json = json;
    this.validator = new QuestionSkeletonValidator();
    this.systemPrompt = PromptResourceReader.read(system);
    this.userPrompt = PromptResourceReader.read(user);
  }

  @Override
  public List<QuestionSkeletonOutput.Skeleton> generate(InterviewBriefSnapshot brief) {
    if (brief == null) {
      throw new IllegalArgumentException("interview brief is required");
    }
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("schemaVersion", 1);
    context.put("interviewSize", brief.interviewSize());
    context.put("difficulty", brief.difficulty());
    context.put("phasePlan", phasePlan(brief));
    context.put("job", Map.of(
        "title", brief.jobTitle(),
        "description", brief.jobDescription()));
    context.put("resume", Map.of(
        "available", brief.resumeId() != null,
        "profile", brief.resume()));

    var skeletonOutput = output.invoke(new AiRequest(
        brief.providerId(),
        brief.modelName(),
        systemPrompt,
        userPrompt.replace("{{CONTEXT_JSON}}", json.encode(context)),
        QuestionSkeletonOutput.class), QuestionSkeletonOutput.class);
    return validator.validate(skeletonOutput, brief.interviewSize());
  }

  private Map<String, Object> phasePlan(InterviewBriefSnapshot brief) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (InterviewPhase phase : GENERATED_PHASES) {
      result.put(phase.name(), Map.of(
          "questionCount", brief.interviewSize().mainQuestionCount(phase)));
    }
    return result;
  }
}
