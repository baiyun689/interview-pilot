package interview.pilot.interview.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import interview.pilot.interview.domain.FixedInterviewReport;

@Component
public class AiFixedReportGenerator implements FixedReportGenerator {
  private final StructuredOutputInvoker output;
  private final PromptJsonEncoder json;
  private final String systemPrompt;
  private final String userPrompt;

  public AiFixedReportGenerator(
      StructuredOutputInvoker output,
      PromptJsonEncoder json,
      @Value("classpath:prompts/fixed-interview-report-system.st") Resource system,
      @Value("classpath:prompts/fixed-interview-report-user.st") Resource user) {
    this.output = output;
    this.json = json;
    this.systemPrompt = PromptResourceReader.read(system);
    this.userPrompt = PromptResourceReader.read(user);
  }

  @Override
  public FixedInterviewReport generate(
      String providerId, String modelName, FixedReportInput input) {
    var phases = input.recruitment()
        ? input.completedTurns().stream().map(FixedReportInput.TurnEvidence::phase).filter(p -> !p.equals("SELF_INTRODUCTION")).distinct().sorted().toList()
        : java.util.Arrays.stream(interview.pilot.interview.domain.InterviewPhase.values()).map(Enum::name).toList();
    String schema = phases.stream().map(p -> "\"" + p + "\": 0到100整数").collect(java.util.stream.Collectors.joining(", "));
    String renderedSystem = systemPrompt.replace("{{PHASE_SCORE_SCHEMA}}", schema)
        + "\n本次 phaseScores 必须恰好包含以下键，不得增加或缺少：" + String.join(", ", phases) + "。";
    var report = output.invoke(new AiRequest(
        providerId, modelName, renderedSystem,
        userPrompt.replace("{{CONTEXT_JSON}}", json.encode(input)),
        FixedInterviewReport.class), FixedInterviewReport.class);
    if (!input.recruitment()) return report;
    var availability = new java.util.EnumMap<interview.pilot.interview.domain.InterviewPhase, String>(interview.pilot.interview.domain.InterviewPhase.class);
    for (var turn : input.completedTurns()) {
      var phase = interview.pilot.interview.domain.InterviewPhase.valueOf(turn.phase());
      if (phase != interview.pilot.interview.domain.InterviewPhase.SELF_INTRODUCTION) {
        var snapshot = (interview.pilot.interview.rag.RagContextSnapshot) turn.ragSnapshot();
        availability.put(phase, snapshot.status().name());
      }
    }
    return new FixedInterviewReport(report.overallScore(), report.phaseScores(), report.strengths(), report.improvements(),
        report.technicalReferences(), report.conflictNotes(), report.summary(), availability);
  }
}
