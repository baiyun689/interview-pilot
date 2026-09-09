package interview.pilot.recruitment.application;

import static interview.pilot.recruitment.application.AssessmentModels.*;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import interview.pilot.ai.StructuredOutputInvoker;
import interview.pilot.ai.model.AiRequest;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;

@Component
public class HiringAnalysisEngine implements HiringWorkProcessor {
  private final StructuredOutputInvoker output;
  private final ObjectMapper json;
  private final Validator validator;
  private final String system;

  public HiringAnalysisEngine(StructuredOutputInvoker output, ObjectMapper json, Validator validator,
      @Value("classpath:prompts/hiring-analysis-system.st") Resource prompt) throws java.io.IOException {
    this.output = output; this.json = json; this.validator = validator;
    this.system = prompt.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  public AnalysisResult analyze(AnalysisInput input) {
    var result = output.invoke(new AiRequest(input.providerId(), input.modelName(), system,
        json.writeValueAsString(Map.of("requirements", input.requirements(), "resumeEvidence", input.resumeEvidence())),
        AnalysisResult.class), AnalysisResult.class);
    validate(input, result);
    return result;
  }

  @Override public String kind() { return "APPLICATION_ANALYSIS"; }
  @Override public Object process(String inputSnapshot) { return analyze(json.readValue(inputSnapshot, AnalysisInput.class)); }

  public void validate(AnalysisInput input, AnalysisResult result) {
    if (result == null || !validator.validate(result).isEmpty()) throw new IllegalArgumentException("无效的岗位证据分析结构");
    var requirementIds = input.requirements().stream().map(Fragment::id).collect(Collectors.toSet());
    var evidenceIds = input.resumeEvidence().stream().map(Fragment::id).collect(Collectors.toSet());
    var seen = new HashSet<String>();
    for (var finding : result.findings()) {
      if (!requirementIds.contains(finding.requirementId()) || !seen.add(finding.requirementId())
          || !evidenceIds.containsAll(finding.evidenceIds())
          || (finding.status() == EvidenceStatus.SUPPORTED && finding.evidenceIds().isEmpty())) {
        throw new IllegalArgumentException("分析引用必须来自当前岗位和提交简历");
      }
    }
    if (!seen.equals(requirementIds)) throw new IllegalArgumentException("分析遗漏了岗位要求片段");
  }
}
