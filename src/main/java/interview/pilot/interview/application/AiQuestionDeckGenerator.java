package interview.pilot.interview.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;

@Component
public class AiQuestionDeckGenerator implements QuestionDeckGenerator {
  private final StructuredOutputInvoker output;
  private final QuestionDeckValidator validator;
  private final PromptJsonEncoder json;
  private final String systemPrompt;
  private final String userPrompt;

  public AiQuestionDeckGenerator(
      StructuredOutputInvoker output,
      QuestionDeckValidator validator,
      PromptJsonEncoder json,
      @Value("classpath:prompts/question-deck-system.st") Resource systemPrompt,
      @Value("classpath:prompts/question-deck-user.st") Resource userPrompt) {
    this.output = output;
    this.validator = validator;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.userPrompt = PromptResourceReader.read(userPrompt);
  }

  @Override
  public PreparedQuestionDeck generate(
      InterviewBriefSnapshot brief,
      Map<InterviewPhase, RagContextSnapshot> ragByPhase) {
    if (brief == null) throw new IllegalArgumentException("interview brief is required");
    var context = new LinkedHashMap<String, Object>();
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
    context.put("ragByPhase", ragByPhase);

    String prompt = userPrompt.replace("{{CONTEXT_JSON}}", json.encode(context));
    QuestionDeckOutput generated = output.invoke(
        new AiRequest(
            brief.providerId(), brief.modelName(), systemPrompt, prompt,
            QuestionDeckOutput.class),
        QuestionDeckOutput.class);
    return validator.validate(generated, brief.interviewSize(), ragByPhase);
  }

  private Map<String, Object> phasePlan(InterviewBriefSnapshot brief) {
    var result = new LinkedHashMap<String, Object>();
    for (InterviewPhase phase : new InterviewPhase[] {
        InterviewPhase.FUNDAMENTALS,
        InterviewPhase.PROJECT_EXPERIENCE,
        InterviewPhase.SCENARIO_TRADEOFF}) {
      result.put(phase.name(), Map.of(
          "questionCount", brief.interviewSize().turnBudget(phase),
          "minimumUsedMainQuestions", brief.interviewSize().minimumMainQuestions(phase)));
    }
    return result;
  }
}
