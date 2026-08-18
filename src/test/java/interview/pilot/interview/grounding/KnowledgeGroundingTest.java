package interview.pilot.interview.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;
import interview.pilot.interview.strategy.TurnDirective;
import interview.pilot.knowledge.config.KnowledgeProperties;
import interview.pilot.knowledge.retrieval.KnowledgeChunk;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.RetrievalStatus;
import interview.pilot.knowledge.retrieval.RetrievedKnowledge;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;

class KnowledgeGroundingTest {
  private static final UUID USER = UUID.randomUUID();
  private static final UUID KB = UUID.randomUUID();
  private static final UUID DOC = UUID.randomUUID();
  private static final ValidatedKnowledgeScope SCOPE = new ValidatedKnowledgeScope(
      USER, List.of(KB), List.of(new ValidatedKnowledgeScope.DocumentRevision(DOC, 3)), "embed-v1");

  @Test
  void disabledDirectiveDoesNotRetrieve() {
    var grounding = new DefaultKnowledgeGrounding(failingRetriever(), properties());

    var snapshot = grounding.ground(SCOPE, GroundingDirective.from(directive(false)));

    assertThat(snapshot.status()).isEqualTo(GroundingStatus.DISABLED);
    assertThat(snapshot.chunks()).isEmpty();
  }

  @Test
  void buildsQueryOnlyFromDirectiveAndCoveredTopics() {
    var grounding = new DefaultKnowledgeGrounding((scope, intent) -> {
      assertThat(intent.query()).isEqualTo(
          "Java并发 failure HARD 可验证失败边界 线程池；追问角度：failure technical 已覆盖:Spring事务");
      assertThat(intent.topK()).isEqualTo(4);
      assertThat(intent.similarityThreshold()).isEqualTo(0.7);
      return RetrievedKnowledge.noMatch(intent.query(), "embed-v1", Duration.ofMillis(3));
    }, properties());

    var snapshot = grounding.ground(
        SCOPE, GroundingDirective.from(directive(true, List.of("Spring事务"))));

    assertThat(snapshot.status()).isEqualTo(GroundingStatus.NO_MATCH);
    assertThat(snapshot.query()).doesNotContain("候选人回答");
  }

  @Test
  void preservesStableSourceMetadataAndRole() {
    KnowledgeRetriever retriever = (scope, intent) -> new RetrievedKnowledge(
        RetrievalStatus.RETRIEVED, intent.query(), "embed-v1",
        List.of(new KnowledgeChunk(
            "point-3-0", DOC, "java.md", 3, 0, "线程池", 0.91,
            "线程池拒绝策略", 12)), Duration.ofMillis(4), null);
    var grounding = new DefaultKnowledgeGrounding(retriever, properties());

    var snapshot = grounding.ground(SCOPE, GroundingDirective.from(directive(true)));

    assertThat(snapshot.status()).isEqualTo(GroundingStatus.RETRIEVED);
    assertThat(snapshot.chunks().getFirst().sourceId()).isEqualTo("point-3-0");
    assertThat(snapshot.chunks().getFirst().role()).isEqualTo(KnowledgeRole.TECHNICAL_REFERENCE);
    assertThat(snapshot.chunks().getFirst().documentRevision()).isEqualTo(3);
    assertThat(snapshot.chunks().getFirst().pageNumber()).isEqualTo(12);
  }

  @Test
  void skillPolicyOverridesConfiguredRetrievalBudget() {
    var policy = new SkillRetrievalPolicy(
        true, List.of("technical"),
        List.of(interview.pilot.interview.skill.GroundingUse.GENERATE_SCENARIO),
        2, 7, 0.86, 900);
    var turn = new TurnDirective(
        "depth", "Java并发", Difficulty.HARD, List.of("边界"),
        InterviewQuestionMode.FAILURE, true, "线程池", "test", "", policy);
    var grounding = new DefaultKnowledgeGrounding((scope, intent) -> {
      assertThat(intent.topK()).isEqualTo(2);
      assertThat(intent.candidateCount()).isEqualTo(7);
      assertThat(intent.similarityThreshold()).isEqualTo(0.86);
      assertThat(intent.contextCharacterBudget()).isEqualTo(900);
      return RetrievedKnowledge.noMatch(intent.query(), "embed", Duration.ZERO);
    }, properties());

    grounding.ground(SCOPE, GroundingDirective.from(turn));
  }

  @Test
  void skillCannotRequestMoreChunksThanThePersistedSnapshotSupports() {
    assertThatThrownBy(() -> new SkillRetrievalPolicy(
        true, List.of("technical"),
        List.of(interview.pilot.interview.skill.GroundingUse.GENERATE_SCENARIO),
        7, 21, 0.8, 2_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("topK is invalid");
  }

  private TurnDirective directive(boolean enabled) {
    return directive(enabled, List.of());
  }

  private TurnDirective directive(boolean enabled, List<String> coveredTopics) {
    return new TurnDirective(
        "depth", "Java并发", Difficulty.HARD, List.of("可验证失败边界"),
        InterviewQuestionMode.FAILURE, enabled, "线程池；追问角度：failure", "test", "",
        new SkillRetrievalPolicy(enabled, List.of("technical"),
            List.of(interview.pilot.interview.skill.GroundingUse.GENERATE_SCENARIO,
                interview.pilot.interview.skill.GroundingUse.VERIFY_FACT)), coveredTopics);
  }

  private KnowledgeRetriever failingRetriever() {
    return (scope, intent) -> { throw new AssertionError("retrieval must not be called"); };
  }

  private KnowledgeProperties properties() {
    return KnowledgeProperties.testDefaults(4, 12, 0.7, 2_000);
  }
}
