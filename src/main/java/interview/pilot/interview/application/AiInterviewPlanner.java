package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.skill.SkillSnapshot;

@Component
public class AiInterviewPlanner implements InterviewPlanner {
  private final StructuredOutputInvoker output;
  private final String systemPrompt;
  private final String userPrompt;
  private final PromptJsonEncoder json;
  private final InterviewPlanCompiler compiler;

  @Autowired
  public AiInterviewPlanner(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/interview-plan-system.st") Resource systemPrompt,
      @Value("classpath:prompts/interview-plan-user.st") Resource userPrompt,
      InterviewPlanCompiler compiler) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(systemPrompt);
    this.userPrompt = PromptResourceReader.read(userPrompt);
    this.compiler = compiler;
  }

  public AiInterviewPlanner(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      Resource systemPrompt,
      Resource userPrompt) {
    this(output, json, systemPrompt, userPrompt, new InterviewPlanCompiler());
  }

  @Override
  public InterviewPlan plan(
      String providerId,
      ResumeProfile resume,
      JobRequirements job,
      Difficulty difficulty,
      int turns) {
    return plan(providerId, resume, job, difficulty, turns, null);
  }

  @Override
  public InterviewPlan plan(
      String providerId, ResumeProfile resume, JobRequirements job,
      Difficulty difficulty, int turns, SkillSnapshot skill) {
    String data = "\n<untrusted_context_json>\n" + json.encode(java.util.Map.of(
        "resume", resume, "job", job, "difficulty", difficulty,
        "turnBudget", turns, "skill", skill == null ? java.util.Map.of() : skill))
        + "\n</untrusted_context_json>";
    InterviewPlan proposal = output.invoke(new AiRequest(
        providerId, systemPrompt, userPrompt + data, InterviewPlan.class), InterviewPlan.class);
    if (proposal == null) return null;
    return compiler.compile(proposal, resume, job, difficulty, turns, skill);
  }
}
