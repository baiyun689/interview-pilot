package interview.pilot.interview.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

class ClasspathInterviewSkillCatalogTest {
  @Test
  void loadsTheEightCuratedSkillsFromClasspath() {
    InterviewSkillCatalog catalog = new ClasspathInterviewSkillCatalog();

    assertThat(catalog.list())
        .extracting(InterviewSkill::id)
        .containsExactly(
            "ai-agent-dev",
            "algorithm",
            "custom",
            "frontend",
            "java-backend",
            "python-backend",
            "system-design",
            "test-development");
    assertThat(catalog.require("java-backend").defaultCompetencies())
        .contains("Java 基础与并发", "Spring 与事务", "MySQL", "Redis");
    assertThat(catalog.require("java-backend").persona()).contains("Java 后端面试统括策略");
    assertThat(catalog.require("java-backend").rubric()).contains("证据评分规则");
    assertThat(catalog.require("java-backend").references()).contains("java.md", "mysql.md");
    assertThat(catalog.require("java-backend").version()).matches("[0-9a-f]{64}");
    assertThat(catalog.require("java-backend").stages())
        .extracting(SkillStageSpec::id)
        .containsExactly("project_deep_dive", "technical_depth", "reliability");
    assertThat(catalog.require("java-backend").competencySpecs())
        .filteredOn(spec -> spec.id().equals("spring_transaction"))
        .singleElement()
        .satisfies(spec -> {
          assertThat(spec.requiredEvidence()).contains("事务传播", "失败处理");
          assertThat(spec.retrievalPolicy().enabled()).isTrue();
        });
  }

  @Test
  void exposesImmutableCatalogValues() {
    InterviewSkill skill = new ClasspathInterviewSkillCatalog().require("java-backend");

    assertThatThrownBy(() -> skill.defaultCompetencies().add("非法修改"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> skill.references().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsUnknownSkillsWithAStableDomainError() {
    InterviewSkillCatalog catalog = new ClasspathInterviewSkillCatalog();

    assertThatThrownBy(() -> catalog.require("missing"))
        .isInstanceOf(InvalidInterviewSkillException.class)
        .hasMessage("未找到面试方向: missing");
  }

  @Test
  void preservesTheCompleteMigratedKnowledgeWhileRuntimeCapabilitiesStayDisabled()
      throws Exception {
    InterviewSkill java = new ClasspathInterviewSkillCatalog().require("java-backend");
    assertThat(java.persona())
        .contains("## 全局决策顺序")
        .contains("competencies.yml")
        .contains("stages.yml")
        .contains("## RAG 使用边界")
        .contains("## 完成标准");

    String metadata = new ClassPathResource("skills/ai-agent-dev/skill.meta.yml")
        .getContentAsString(StandardCharsets.UTF_8);
    assertThat(metadata)
        .contains("defaultStages:")
        .contains("references:")
        .contains("retrievalScopes:")
        .contains("ragKeywords:")
        .contains("  - rag.search")
        .contains("ragEnabled: false")
        .contains("toolsEnabled: false")
        .contains("allowedTools:")
        .doesNotContain("depthSignalKeywords:")
        .doesNotContain("concreteEvidenceKeywords:");
  }

  @Test
  void javaBackendLoadsItsStrategyFromFivePurposeSpecificFiles() throws Exception {
    InterviewSkill java = new ClasspathInterviewSkillCatalog().require("java-backend");
    String metadata = resource("skill.meta.yml");
    String competencies = resource("competencies.yml");
    String stages = resource("stages.yml");

    assertThat(metadata)
        .contains("schemaVersion: 3", "competencies: competencies.yml", "stages: stages.yml")
        .doesNotContain("defaultCompetencies:", "requiredEvidence:", "questionModes:");
    assertThat(competencies)
        .contains("defaultCompetencies:", "requiredEvidence:", "stageId:");
    assertThat(stages)
        .contains("id: project_deep_dive", "id: technical_depth", "id: reliability");
    assertThat(java.competencySpecs())
        .filteredOn(spec -> spec.id().equals("distributed_reliability"))
        .singleElement()
        .satisfies(spec -> assertThat(spec.stageId()).isEqualTo("reliability"));
    assertThat(java.stages())
        .extracting(SkillStageSpec::order)
        .containsExactly(10, 20, 30);
    assertThat(java.stages().getFirst().exitCriteria())
        .contains("个人贡献边界清晰", "至少一项结果可核验");
    assertThat(java.snapshot().schemaVersion()).isEqualTo(3);
  }

  private String resource(String filename) throws Exception {
    return new ClassPathResource("skills/java-backend/" + filename)
        .getContentAsString(StandardCharsets.UTF_8);
  }
}
