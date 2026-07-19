package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.QuestionContext;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;

@Component
public class AiQuestionGenerator implements QuestionGenerator {
  private final StructuredOutputInvoker output;
  private final String systemPrompt;
  private final String firstPrompt;
  private final String nextPrompt;
  private final PromptJsonEncoder json;

  public AiQuestionGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/question-system.st") Resource systemPrompt,
      @Value("classpath:prompts/first-question-user.st") Resource firstPrompt,
      @Value("classpath:prompts/next-question-user.st") Resource nextPrompt) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.firstPrompt = PromptResourceReader.read(firstPrompt);
    this.nextPrompt = PromptResourceReader.read(nextPrompt);
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
    var ragMap = ragSnapshot.status() == interview.pilot.interview.rag.RagStatus.RETRIEVED
        ? ragSnapshot : java.util.Map.of("status", ragSnapshot.status().name());
    String data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "plan", plan, "resume", resume, "job", job,
        "skill", skill == null ? java.util.Map.of() : skill,
        "retrievedKnowledge", ragMap))
        + "\n</untrusted_context_json>";
    return output.invoke(new AiRequest(
        providerId, systemPrompt, firstPrompt + data, GeneratedQuestion.class),
        GeneratedQuestion.class);
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
    var rag = context.ragSnapshot() != null ? context.ragSnapshot()
        : RagContextSnapshot.notConfigured();
    var data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "context", context, "validatedDecision", decision,
        "retrievedKnowledge", rag.status() == RagStatus.RETRIEVED ? rag
            : java.util.Map.of("status", rag.status().name())))
        + "\n</untrusted_context_json>";
    return output.invoke(new AiRequest(
        providerId, expectedModel, systemPrompt, nextPrompt + data, GeneratedQuestion.class),
        GeneratedQuestion.class);
  }
}
