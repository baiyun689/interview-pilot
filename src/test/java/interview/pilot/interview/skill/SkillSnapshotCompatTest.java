package interview.pilot.interview.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 旧快照 JSON 解码兼容：字段增删不得破坏 v1~v4 会话。 */
class SkillSnapshotCompatTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void missingRedFlagsDecodesToEmptyList() throws Exception {
    String legacyV4 = """
        {"id":"java-backend","name":"Java 后端","description":"旧快照","group":"JOB",
         "defaultCompetencies":[],"persona":"旧 persona","rubric":"旧 rubric",
         "references":["legacy.md"],"version":"abc","schemaVersion":4,
         "stages":[{"id":"project_deep_dive","purpose":"p","order":10,
                    "entryCriteria":["a"],"exitCriteria":["b"]},
                   {"id":"technical_depth","purpose":"p","order":20,
                    "entryCriteria":["a"],"exitCriteria":["b"]}],
         "competencySpecs":[{"id":"java_concurrency","name":"Java 并发",
             "objective":"o","requiredEvidence":["e1"],"questionModes":["PROJECT"],
             "followUpAxes":["axis"],"redFlags":["rf"],"followUpLimit":2,
             "retrievalPolicy":{"enabled":true,"scopes":["java"],
                                "keywords":["k"],"allowedUses":["VERIFY_FACT"]},
             "stageId":"technical_depth"}],
         "retrievalPolicy":{"enabled":false,"scopes":[],"keywords":[],
                            "allowedUses":[]}}""";

    SkillSnapshot snapshot = objectMapper.readValue(legacyV4, SkillSnapshot.class);

    // 新增字段缺省 → 空列表，不抛异常
    assertThat(snapshot.redFlags()).isEmpty();
    // 多余字段（references/entryCriteria/exitCriteria/keywords）被忽略，解码不抛异常
    assertThat(snapshot.id()).isEqualTo("java-backend");
    assertThat(snapshot.schemaVersion()).isEqualTo(4);
    assertThat(snapshot.stages()).hasSize(2);
    assertThat(snapshot.competencySpecs()).singleElement()
        .satisfies(spec -> {
          assertThat(spec.id()).isEqualTo("java_concurrency");
          assertThat(spec.retrievalPolicy().scopes()).containsExactly("java");
        });
  }

  @Test
  void defaultStagesHaveTheThreeStandardPhases() {
    List<SkillStageSpec> stages = SkillStageSpec.DEFAULT_STAGES;
    assertThat(stages)
        .extracting(SkillStageSpec::id)
        .containsExactly("project_deep_dive", "technical_depth", "reliability");
    assertThat(stages).extracting(SkillStageSpec::order).containsExactly(10, 20, 30);
  }
}
