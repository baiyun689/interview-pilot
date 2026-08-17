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
    assertThat(catalog.require("java-backend").defaultCompetencies())
        .contains("Java 基础与并发", "Spring 与事务", "MySQL", "Redis");
    assertThat(catalog.require("java-backend").persona()).contains("Java 后端面试策略");
    assertThat(catalog.require("java-backend").rubric()).contains("证据评分规则");
    assertThat(catalog.require("java-backend").references()).isEmpty();
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
  void loadsTheConciseJavaPersonaWithoutInternalRagEnums() {
    InterviewSkill java = new ClasspathInterviewSkillCatalog().require("java-backend");
    assertThat(java.persona())
        .contains("## 决策原则")
        .contains("## RAG 资料使用规则")
        .doesNotContain("GENERATE_SCENARIO", "VERIFY_FACT", "competencies.yml", "stages.yml");
  }

  @Test
  void keepsTheCustomSkillOnTheLegacyContract() {
    InterviewSkill custom = new ClasspathInterviewSkillCatalog().require("custom");

    assertThat(custom.schemaVersion()).isEqualTo(1);
    assertThat(custom.defaultCompetencies()).contains("岗位需求理解", "技术正确性", "方案权衡");
  }

  @Test
  void javaBackendLoadsItsStrategyFromFivePurposeSpecificFiles() throws Exception {
    InterviewSkill java = new ClasspathInterviewSkillCatalog().require("java-backend");
    String metadata = resource("skill.meta.yml");
    String competencies = resource("competencies.yml");
    String stages = resource("stages.yml");

    assertThat(metadata)
        .contains("schemaVersion: 4", "icon: code")
        .doesNotContain(
            "resources:", "defaultCompetencies:", "retrievalScopes:", "ragKeywords:",
            "allowedTools:", "references:", "runtime:", "toolsEnabled:");
    assertThat(competencies)
        .contains("evidence:", "modes:", "probes:", "stage:", "ragScopes:")
        .doesNotContain(
            "defaultCompetencies:", "requiredEvidence:", "questionModes:", "followUpAxes:",
            "redFlags:", "followUpLimit:", "rag:", "allowedUses:", "topK:",
            "candidateCount:", "minimumScore:", "contextCharacterBudget:");
    assertThat(stages)
        .contains("id: project_deep_dive", "id: technical_depth", "id: reliability")
        .doesNotContain("order:", "entryCriteria:", "exitCriteria:");
    assertThat(java.competencySpecs())
        .filteredOn(spec -> spec.id().equals("distributed_reliability"))
        .singleElement()
        .satisfies(spec -> assertThat(spec.stageId()).isEqualTo("reliability"));
    assertThat(java.stages())
        .extracting(SkillStageSpec::order)
        .containsExactly(10, 20, 30);
    assertThat(java.stages().getFirst().exitCriteria()).isEmpty();
    assertThat(java.defaultCompetencies())
        .containsExactlyElementsOf(java.competencySpecs().stream().map(CompetencySpec::name).toList());
    assertThat(java.retrievalPolicy().enabled()).isFalse();
    assertThat(java.snapshot().schemaVersion()).isEqualTo(4);
  }

  @Test
  void everyCuratedInterviewDirectionUsesTheConciseV4Contract() {
    InterviewSkillCatalog catalog = new ClasspathInterviewSkillCatalog();
    List<String> migrated = catalog.list().stream()
        .filter(skill -> skill.schemaVersion() == 4)
        .map(InterviewSkill::id)
        .toList();
    assertThat(migrated).hasSize(7).doesNotContain("custom");
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
      Set<String> stageIds = skill.stages().stream().map(SkillStageSpec::id).collect(
          java.util.stream.Collectors.toSet());

      assertThat(skill.schemaVersion()).as(id).isEqualTo(4);
      assertThat(skill.references()).as(id).isEmpty();
      assertThat(skill.defaultCompetencies()).as(id)
          .containsExactlyElementsOf(skill.competencySpecs().stream().map(CompetencySpec::name).toList());
      assertThat(skill.competencySpecs()).as(id).allSatisfy(spec -> {
        assertThat(spec.stageId()).isIn(stageIds);
        assertThat(spec.requiredEvidence()).isNotEmpty();
        assertThat(spec.questionModes()).isNotEmpty();
        assertThat(spec.followUpAxes()).isNotEmpty();
        assertThat(spec.retrievalPolicy().scopes()).isSubsetOf(allowedScopes.get(id));
      });
    }

    assertThat(catalog.require("algorithm").competencySpecs())
        .filteredOn(spec -> spec.id().equals("complexity_analysis"))
        .singleElement().satisfies(spec -> assertThat(spec.retrievalPolicy().scopes())
            .containsExactly("algorithm-data-structures", "complexity"));
    assertThat(catalog.require("frontend").competencySpecs())
        .filteredOn(spec -> spec.id().equals("project_ownership"))
        .singleElement().satisfies(spec -> assertThat(spec.retrievalPolicy().enabled()).isFalse());
  }

  private String resource(String filename) throws Exception {
    return new ClassPathResource("skills/java-backend/" + filename)
        .getContentAsString(StandardCharsets.UTF_8);
  }
}
