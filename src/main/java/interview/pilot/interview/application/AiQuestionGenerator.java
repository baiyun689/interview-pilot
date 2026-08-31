package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.QuestionContext;
import interview.pilot.interview.domain.QuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.interview.strategy.TurnDirective;

@Component
public class AiQuestionGenerator implements QuestionGenerator {
  private final StructuredOutputInvoker output;
  private final String systemPrompt;
  private final String firstPrompt;
  private final String nextPrompt;
  private final String deckSystemPrompt;
  private final String deckUserPrompt;
  private final String followUpSystemPrompt;
  private final String followUpUserPrompt;
  private final PromptJsonEncoder json;

  @Autowired
  public AiQuestionGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/question-system.st") Resource systemPrompt,
      @Value("classpath:prompts/first-question-user.st") Resource firstPrompt,
      @Value("classpath:prompts/next-question-user.st") Resource nextPrompt,
      @Value("classpath:prompts/question-deck-system.st") Resource deckSystemPrompt,
      @Value("classpath:prompts/question-deck-user.st") Resource deckUserPrompt,
      @Value("classpath:prompts/follow-up-question-system.st") Resource followUpSystemPrompt,
      @Value("classpath:prompts/follow-up-question-user.st") Resource followUpUserPrompt) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.firstPrompt = PromptResourceReader.read(firstPrompt);
    this.nextPrompt = PromptResourceReader.read(nextPrompt);
    this.deckSystemPrompt = PromptResourceReader.read(deckSystemPrompt);
    this.deckUserPrompt = PromptResourceReader.read(deckUserPrompt);
    this.followUpSystemPrompt = PromptResourceReader.read(followUpSystemPrompt);
    this.followUpUserPrompt = PromptResourceReader.read(followUpUserPrompt);
  }

  public AiQuestionGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      Resource systemPrompt,
      Resource firstPrompt,
      Resource nextPrompt) {
    this(output, json, systemPrompt, firstPrompt, nextPrompt,
        new ClassPathResource("prompts/question-deck-system.st"),
        new ClassPathResource("prompts/question-deck-user.st"),
        new ClassPathResource("prompts/follow-up-question-system.st"),
        new ClassPathResource("prompts/follow-up-question-user.st"));
  }

  @Override
  public GeneratedQuestion generateFollowUpQuestion(
      String providerId, String expectedModel, QuestionContext context,
      InterviewDecision decision, TurnDirective directive) {
    var rag = context.ragSnapshot() != null
        && context.ragSnapshot().status() == RagStatus.RETRIEVED
        ? context.ragSnapshot() : java.util.Map.of("status", "NOT_REQUESTED");
    var data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "question", context.previousQuestion(),
        "answer", context.previousAnswer(),
        "competency", directive == null ? decision.targetCompetency() : directive.competency(),
        "probeFocus", directive == null ? decision.probeFocus() : directive.probeFocus(),
        "difficulty", context.difficulty(),
        "retrievedKnowledge", rag)) + "\n</untrusted_context_json>";
    return output.invoke(new AiRequest(
        providerId, expectedModel, followUpSystemPrompt, followUpUserPrompt + data,
        GeneratedQuestionOutput.class), GeneratedQuestionOutput.class).toDomain();
  }

  @Override
  public QuestionDeck generatePrimaryQuestions(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill, RagContextSnapshot ragSnapshot,
      TurnDirective directive) {
    var rag = ragSnapshot != null && ragSnapshot.status() == RagStatus.RETRIEVED
        ? ragSnapshot : java.util.Map.of("status", ragSnapshot == null ? "NOT_CONFIGURED" : ragSnapshot.status().name());
    var data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "plan", plan, "resume", resume, "job", job,
        "skill", skill == null ? java.util.Map.of() : skill,
        "difficulty", directive == null ? "MEDIUM" : directive.difficulty().name(),
        "retrievedKnowledge", rag)) + "\n</untrusted_context_json>";
    return output.invoke(new AiRequest(
        providerId, deckSystemPrompt, deckUserPrompt + data, QuestionDeckOutput.class),
        QuestionDeckOutput.class).toDomain();
  }

  @Override
  public GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume, JobRequirements job) {
    return firstQuestion(providerId, plan, resume, job, null);
  }

  @Override
  public GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill) {
    return firstQuestion(providerId, plan, resume, job, skill, RagContextSnapshot.notConfigured());
  }

  @Override
  public GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill, RagContextSnapshot ragSnapshot) {
    return firstQuestion(providerId, plan, resume, job, skill, ragSnapshot, null);
  }

  @Override
  public GeneratedQuestion firstQuestion(
      String providerId, InterviewPlan plan, ResumeProfile resume,
      JobRequirements job, SkillSnapshot skill, RagContextSnapshot ragSnapshot,
      TurnDirective directive) {
    var ragMap = ragSnapshot.status() == interview.pilot.interview.rag.RagStatus.RETRIEVED
        ? ragSnapshot : java.util.Map.of("status", ragSnapshot.status().name());
    String data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "plan", plan, "resume", resume, "job", job,
        "skill", skill == null ? java.util.Map.of() : skill,
        "turnDirective", directive == null ? java.util.Map.of() : directive,
        "retrievedKnowledge", ragMap))
        + "\n</untrusted_context_json>";
    return output.invoke(new AiRequest(
        providerId, systemPrompt, firstPrompt + data, GeneratedQuestionOutput.class),
        GeneratedQuestionOutput.class).toDomain();
  }

  @Override
  public GeneratedQuestion nextQuestion(
      String providerId, QuestionContext context, InterviewDecision decision) {
    return nextQuestion(providerId, null, context, decision);
  }

  @Override
  public GeneratedQuestion nextQuestion(
      String providerId,
      String expectedModel,
      QuestionContext context,
      InterviewDecision decision) {
    return nextQuestion(providerId, expectedModel, context, decision, (TurnDirective) null);
  }

  @Override
  public GeneratedQuestion nextQuestion(
      String providerId,
      String expectedModel,
      QuestionContext context,
      InterviewDecision decision,
      TurnDirective directive) {
    var rag = context.ragSnapshot() != null ? context.ragSnapshot()
        : RagContextSnapshot.notConfigured();
    var data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "context", context, "validatedDecision", decision,
        "turnDirective", directive == null ? java.util.Map.of() : directive,
        "retrievedKnowledge", rag.status() == RagStatus.RETRIEVED ? rag
            : java.util.Map.of("status", rag.status().name())))
        + "\n</untrusted_context_json>";
    return output.invoke(new AiRequest(
        providerId, expectedModel, systemPrompt, nextPrompt + data, GeneratedQuestionOutput.class),
        GeneratedQuestionOutput.class).toDomain();
  }
}
