package interview.pilot.interview.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    assertThat(catalog.require("java-backend").defaultCompetencies()).isEmpty();
    assertThat(catalog.require("java-backend").persona()).contains("Java 后端面试官手册");
    assertThat(catalog.require("java-backend").rubric()).contains("Java 后端面试官手册");
    assertThat(catalog.require("java-backend").redFlags()).hasSize(4);
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
          assertThat(spec.retrievalPolicy().scopes()).containsExactly("spring", "transaction");
          assertThat(spec.retrievalPolicy().allowedUses())
              .containsExactly(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT);
          assertThat(spec.retrievalPolicy().topK()).isNull();
        });
  }

  @Test
  void exposesImmutableCatalogValues() {
    InterviewSkill skill = new ClasspathInterviewSkillCatalog().require("java-backend");

    assertThatThrownBy(() -> skill.defaultCompetencies().add("非法修改"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> skill.redFlags().add("x"))
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
  void loadsTheConciseJavaPersonaWithoutInternalRagEnums() {
    InterviewSkill java = new ClasspathInterviewSkillCatalog().require("java-backend");
    assertThat(java.persona())
        .contains("## 决策原则")
        .contains("## RAG 资料使用规则")
        .contains("## 反套路原则")
        .contains("## 难度锚点")
        .doesNotContain(
            "GENERATE_SCENARIO", "VERIFY_FACT", "competencies.yml", "stages.yml",
            "schemaVersion: 4");
  }

  @Test
  void keepsTheCustomSkillOnTheLegacyContract() {
    InterviewSkill custom = new ClasspathInterviewSkillCatalog().require("custom");

    assertThat(custom.schemaVersion()).isEqualTo(1);
    assertThat(custom.defaultCompetencies()).contains("岗位需求理解", "技术正确性", "方案权衡");
  }

  @Test
  void javaBackendLoadsItsStrategyFromTwoPurposeSpecificFiles() throws Exception {
    InterviewSkill java = new ClasspathInterviewSkillCatalog().require("java-backend");
    String metadata = resource("skill.yml");
    String handbook = resource("SKILL.md");

    assertThat(metadata)
        .contains("schemaVersion: 5", "icon: code", "redFlags:")
        .doesNotContain(
            "resources:", "defaultCompetencies:", "retrievalScopes:", "ragKeywords:",
            "allowedTools:", "references:", "runtime:", "toolsEnabled:", "followUpLimit:",
            "topK:", "candidateCount:", "minimumScore:", "contextCharacterBudget:");
    assertThat(handbook)
        .contains("## 岗位考察重点", "## 反套路原则", "## 难度锚点", "## 五级评分锚点", "## 证据规则");
    assertThat(java.competencySpecs())
        .filteredOn(spec -> spec.id().equals("distributed_reliability"))
        .singleElement()
        .satisfies(spec -> assertThat(spec.stageId()).isBlank());
    assertThat(java.stages())
        .extracting(SkillStageSpec::order)
        .containsExactly(10, 20, 30);
    assertThat(java.redFlags()).hasSize(4);
    assertThat(java.defaultCompetencies()).isEmpty();
    assertThat(java.retrievalPolicy().enabled()).isFalse();
    assertThat(java.snapshot().schemaVersion()).isEqualTo(5);
  }

  @Test
  void everyCuratedInterviewDirectionUsesTheConciseV5Contract() {
    InterviewSkillCatalog catalog = new ClasspathInterviewSkillCatalog();
    List<String> migrated = catalog.list().stream()
        .filter(skill -> skill.schemaVersion() == 5)
        .map(InterviewSkill::id)
        .toList();
    assertThat(migrated).containsExactly("ai-agent-dev", "algorithm", "java-backend");
    Map<String, Set<String>> allowedScopes = Map.of(
        "ai-agent-dev", Set.of("ai-agent", "tool-use", "rag", "mcp", "system-design"),
        "algorithm", Set.of("algorithm-data-structures", "complexity", "edge-cases"),
        "frontend", Set.of("javascript", "react-vue", "browser", "css", "frontend-performance"),
        "java-backend", Set.of("java", "concurrency", "spring", "transaction", "mysql",
            "database", "redis", "cache", "distributed", "high-availability", "mq"),
        "python-backend", Set.of("python", "django-flask", "database", "distributed"),
        "system-design", Set.of(
            "system-design-scenarios", "distributed", "high-availability", "database", "mq"),
        "test-development", Set.of("test-development", "automation", "quality", "ci"));

    for (String id : migrated) {
      InterviewSkill skill = catalog.require(id);

      assertThat(skill.schemaVersion()).as(id).isEqualTo(5);
      assertThat(skill.defaultCompetencies()).as(id).isEmpty();
      assertThat(skill.redFlags()).as(id).isNotEmpty();
      assertThat(skill.competencySpecs()).as(id).allSatisfy(spec -> {
        assertThat(spec.requiredEvidence()).isNotEmpty();
        assertThat(spec.questionModes()).isNotEmpty();
        assertThat(spec.followUpAxes()).isNotEmpty();
        assertThat(spec.retrievalPolicy().scopes()).isSubsetOf(allowedScopes.get(id));
        assertThat(spec.followUpLimit()).isEqualTo(2);
      });
    }

    assertThat(catalog.require("algorithm").competencySpecs())
        .filteredOn(spec -> spec.id().equals("complexity_analysis"))
        .singleElement()
        .satisfies(spec -> assertThat(spec.retrievalPolicy().scopes())
            .containsExactly("algorithm-data-structures", "complexity"));
  }

  private String resource(String filename) throws Exception {
    return new ClassPathResource("skills/java-backend/" + filename)
        .getContentAsString(StandardCharsets.UTF_8);
  }
}
