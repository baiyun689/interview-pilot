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
    assertThat(catalog.require("java-backend").persona()).contains("Java 后端面试 Skill");
    assertThat(catalog.require("java-backend").rubric()).contains("评分标准");
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
        .contains("## 适用场景")
        .contains("PROJECT_OWNERSHIP")
        .contains("## 好问题长什么样")
        .contains("## 领域出题角度（按主题取材，非流程指令）")
        .contains("## 不同深度的代表性问法（供锚定难度，不自行控制难度）")
        .contains("把团队成果说成个人成果");

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
}
