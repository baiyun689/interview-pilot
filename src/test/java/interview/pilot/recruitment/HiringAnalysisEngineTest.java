package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import static interview.pilot.recruitment.application.AssessmentModels.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import interview.pilot.recruitment.application.HiringAnalysisEngine;
import interview.pilot.ai.StructuredOutputInvoker;
import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;

class HiringAnalysisEngineTest {
  @Test void rejectsInventedReferencesAndUnsupportedPositiveFindings() throws Exception {
    try (var validators = Validation.buildDefaultValidatorFactory()) {
      var engine = new HiringAnalysisEngine(org.mockito.Mockito.mock(StructuredOutputInvoker.class), new ObjectMapper(), validators.getValidator(), new ClassPathResource("prompts/hiring-analysis-system.st"));
      var input = new AnalysisInput("test", "test-model", "v1", List.of(new Fragment("j1", "缓存一致性")), List.of(new Fragment("r1", "使用过 Redis")));
      var valid = new AnalysisResult(List.of(new Finding("j1", EvidenceStatus.SUPPORTED, List.of("r1"), "有相关描述", List.of("如何处理失败？"))));
      assertThatCode(() -> engine.validate(input, valid)).doesNotThrowAnyException();
      var forged = new AnalysisResult(List.of(new Finding("j1", EvidenceStatus.SUPPORTED, List.of("r99"), "捏造来源", List.of())));
      assertThatThrownBy(() -> engine.validate(input, forged)).isInstanceOf(IllegalArgumentException.class);
      var noEvidence = new AnalysisResult(List.of(new Finding("j1", EvidenceStatus.SUPPORTED, List.of(), "没有依据却肯定", List.of())));
      assertThatThrownBy(() -> engine.validate(input, noEvidence)).isInstanceOf(IllegalArgumentException.class);
      var duplicate = new AnalysisResult(List.of(valid.findings().getFirst(), valid.findings().getFirst()));
      assertThatThrownBy(() -> engine.validate(input, duplicate)).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
