package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

class QuestionDeckValidatorTest {
  private final QuestionDeckValidator validator = new QuestionDeckValidator();

  @Test
  void rejectsMissingCollectionsAsInvalidDeckInsteadOfTreatingThemAsGatewayFailures() {
    assertThatThrownBy(() -> validator.validate(
        new QuestionDeckOutput(1, null), InterviewSize.QUICK, ragByPhase()))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("questions");

    var questions = quickQuestions();
    var first = questions.getFirst();
    var missingFocusPoints = new QuestionDeckOutput.Question(
        first.phase(), first.sequence(), first.topic(), first.question(), null,
        first.groundingMode(), first.evidenceRefs(), first.fallbackFollowUp());
    var modified = new java.util.ArrayList<>(questions);
    modified.set(0, missingFocusPoints);
    assertThatThrownBy(() -> validator.validate(
        new QuestionDeckOutput(1, modified), InterviewSize.QUICK, ragByPhase()))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("focusPoints");
  }

  @Test
  void acceptsAnExactStandardDeckAndPreservesPhaseEvidence() {
    var output = new QuestionDeckOutput(1, standardQuestions());

    var deck = validator.validate(output, InterviewSize.STANDARD, ragByPhase());

    assertThat(deck.questions()).hasSize(8);
    assertThat(deck.questionsFor(InterviewPhase.FUNDAMENTALS)).hasSize(3);
    assertThat(deck.questionsFor(InterviewPhase.PROJECT_EXPERIENCE)).hasSize(3);
    assertThat(deck.questionsFor(InterviewPhase.SCENARIO_TRADEOFF)).hasSize(2);
    assertThat(deck.questionsFor(InterviewPhase.PROJECT_EXPERIENCE).getFirst().evidenceRefs())
        .containsExactly("project-point");
  }

  @Test
  void rejectsTheWholeDeckWhenAnyPhaseCountIsWrong() {
    var questions = new ArrayList<>(standardQuestions());
    questions.removeLast();

    assertThatThrownBy(() -> validator.validate(
        new QuestionDeckOutput(1, questions), InterviewSize.STANDARD, ragByPhase()))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("SCENARIO_TRADEOFF requires exactly 2 questions");
  }

  @Test
  void requiresFallbackForEveryMainQuestionPhaseAndRejectsCrossPhaseEvidence() {
    var invalidFundamentals = new ArrayList<>(standardQuestions());
    invalidFundamentals.set(0, question(
        InterviewPhase.FUNDAMENTALS, 1, "Java 并发", "解释 volatile 的可见性边界以及它不能保证什么？",
        GroundingMode.GENERAL, List.of(), null));

    assertThatThrownBy(() -> validator.validate(
        new QuestionDeckOutput(1, invalidFundamentals), InterviewSize.STANDARD, ragByPhase()))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("fallbackFollowUp");

    var invalidEvidence = new ArrayList<>(standardQuestions());
    invalidEvidence.set(3, question(
        InterviewPhase.PROJECT_EXPERIENCE, 1, "缓存一致性", "请说明项目中缓存与数据库更新的失败窗口和补偿设计。",
        GroundingMode.KNOWLEDGE_ASSISTED, List.of("scenario-point"),
        "如果依赖超时后出现重复执行，你会如何发现并恢复？"));

    assertThatThrownBy(() -> validator.validate(
        new QuestionDeckOutput(1, invalidEvidence), InterviewSize.STANDARD, ragByPhase()))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("current phase RAG snapshot");
  }

  @Test
  void rejectsDuplicateQuestionTextAndNonContinuousSequence() {
    var questions = new ArrayList<>(standardQuestions());
    var first = questions.getFirst();
    questions.set(1, question(
        InterviewPhase.FUNDAMENTALS, 3, "重复主题", first.question(),
        GroundingMode.GENERAL, List.of(), null));

    assertThatThrownBy(() -> validator.validate(
        new QuestionDeckOutput(1, questions), InterviewSize.STANDARD, ragByPhase()))
        .isInstanceOf(InvalidQuestionDeckException.class);
  }

  private List<QuestionDeckOutput.Question> standardQuestions() {
    return List.of(
        question(InterviewPhase.FUNDAMENTALS, 1, "Java 并发", "解释 volatile 的可见性边界以及它不能保证什么？", GroundingMode.GENERAL, List.of(), "如果多个线程还会执行复合写操作，你会怎样保证原子性？"),
        question(InterviewPhase.FUNDAMENTALS, 2, "Spring 事务", "Spring 声明式事务在哪些调用边界下可能失效，为什么？", GroundingMode.GENERAL, List.of(), "如果事务注解没有生效，你会怎样定位代理调用边界？"),
        question(InterviewPhase.FUNDAMENTALS, 3, "MySQL 索引", "面对一条慢查询，你会如何结合执行计划定位索引问题？", GroundingMode.GENERAL, List.of(), "如果增加索引后写入延迟明显上升，你会如何权衡和验证？"),
        question(InterviewPhase.PROJECT_EXPERIENCE, 1, "缓存一致性", "请说明项目中缓存与数据库更新的失败窗口和补偿设计。", GroundingMode.KNOWLEDGE_ASSISTED, List.of("project-point"), "如果依赖超时后出现重复执行，你会如何发现并恢复？"),
        question(InterviewPhase.PROJECT_EXPERIENCE, 2, "个人贡献", "选择一个真实项目，说明你的职责边界和最关键的实现决策。", GroundingMode.GENERAL, List.of(), "如果重新实现这部分，你会优先改变哪个设计决定？"),
        question(InterviewPhase.PROJECT_EXPERIENCE, 3, "故障处理", "描述一次你负责模块的故障定位过程以及用于验证恢复的指标。", GroundingMode.GENERAL, List.of(), "如果故障再次发生，你会增加哪些自动化保护措施？"),
        question(InterviewPhase.SCENARIO_TRADEOFF, 1, "消息幂等", "订单消息可能重复且乱序时，请设计消费流程并说明一致性取舍。", GroundingMode.KNOWLEDGE_ASSISTED, List.of("scenario-point"), "如果幂等存储不可用，你会怎样降级并控制风险？"),
        question(InterviewPhase.SCENARIO_TRADEOFF, 2, "容量演进", "接口流量十倍增长且预算有限时，你会如何分阶段扩容并验证效果？", GroundingMode.GENERAL, List.of(), "如果扩容后尾延迟仍然升高，你会先检查哪些指标？"));
  }

  private List<QuestionDeckOutput.Question> quickQuestions() {
    return standardQuestions().stream()
        .filter(question -> question.sequence() <= switch (question.phase()) {
          case FUNDAMENTALS, PROJECT_EXPERIENCE -> 2;
          case SCENARIO_TRADEOFF -> 1;
          case SELF_INTRODUCTION -> 0;
        })
        .toList();
  }

  private QuestionDeckOutput.Question question(
      InterviewPhase phase, int sequence, String topic, String question,
      GroundingMode mode, List<String> refs, String fallback) {
    return new QuestionDeckOutput.Question(
        phase, sequence, topic, question, List.of("失败窗口", "监控与补偿"),
        mode, refs, fallback);
  }

  private Map<InterviewPhase, RagContextSnapshot> ragByPhase() {
    var result = new EnumMap<InterviewPhase, RagContextSnapshot>(InterviewPhase.class);
    result.put(InterviewPhase.FUNDAMENTALS, RagContextSnapshot.notConfigured());
    result.put(InterviewPhase.PROJECT_EXPERIENCE, retrieved("project-point"));
    result.put(InterviewPhase.SCENARIO_TRADEOFF, retrieved("scenario-point"));
    return result;
  }

  private RagContextSnapshot retrieved(String pointId) {
    var chunk = new RagContextSnapshot.Chunk(
        pointId, UUID.randomUUID(), "reference.md", 0, 0.9, "技术参考内容");
    return new RagContextSnapshot(RagStatus.RETRIEVED, "query", "v1", List.of(chunk), null);
  }
}
