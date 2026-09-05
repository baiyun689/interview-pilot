package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;

class QuestionSkeletonValidatorTest {

  private final QuestionSkeletonValidator validator = new QuestionSkeletonValidator();

  @Test
  void acceptsAStandardSkeletonDeckInPhaseOrder() {
    var skeletons = validator.validate(
        new QuestionSkeletonOutput(1, standardSkeletons()), InterviewSize.STANDARD);

    assertThat(skeletons).hasSize(8);
    assertThat(skeletons.getFirst().phase()).isEqualTo(InterviewPhase.FUNDAMENTALS);
    assertThat(skeletons.getLast().phase()).isEqualTo(InterviewPhase.SCENARIO_TRADEOFF);
  }

  @Test
  void rejectsAWrongPhaseCount() {
    var questions = new ArrayList<>(standardSkeletons());
    questions.removeLast();

    assertThatThrownBy(() -> validator.validate(
        new QuestionSkeletonOutput(1, questions), InterviewSize.STANDARD))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("SCENARIO_TRADEOFF requires exactly 2 questions");
  }

  @Test
  void rejectsTooFewFocusPoints() {
    var questions = new ArrayList<>(standardSkeletons());
    questions.set(0, skeleton(InterviewPhase.FUNDAMENTALS, 1, List.of("只有一个关注点")));

    assertThatThrownBy(() -> validator.validate(
        new QuestionSkeletonOutput(1, questions), InterviewSize.STANDARD))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("focusPoints");
  }

  @Test
  void rejectsBlankKnowledgePoint() {
    var questions = new ArrayList<>(standardSkeletons());
    questions.set(0, skeleton(
        InterviewPhase.FUNDAMENTALS, 1, List.of("核心原理", "边界"), " "));

    assertThatThrownBy(() -> validator.validate(
        new QuestionSkeletonOutput(1, questions), InterviewSize.STANDARD))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("knowledgePoint");
  }

  @Test
  void rejectsEmptyRetrievalKeywords() {
    var valid = standardSkeletons().getFirst();
    var noKeywords = new QuestionSkeletonOutput.Skeleton(
        valid.phase(), valid.sequence(), valid.topic(), valid.question(),
        valid.focusPoints(), valid.knowledgePoint(), List.of(), valid.fallbackFollowUp());
    var questions = new ArrayList<>(standardSkeletons());
    questions.set(0, noKeywords);

    assertThatThrownBy(() -> validator.validate(
        new QuestionSkeletonOutput(1, questions), InterviewSize.STANDARD))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("retrievalKeywords");
  }

  @Test
  void rejectsNonContinuousSequence() {
    var questions = new ArrayList<>(standardSkeletons());
    questions.set(1, skeleton(InterviewPhase.FUNDAMENTALS, 3));

    assertThatThrownBy(() -> validator.validate(
        new QuestionSkeletonOutput(1, questions), InterviewSize.STANDARD))
        .isInstanceOf(InvalidQuestionDeckException.class)
        .hasMessageContaining("continuous");
  }

  private List<QuestionSkeletonOutput.Skeleton> standardSkeletons() {
    var list = new ArrayList<QuestionSkeletonOutput.Skeleton>();
    for (int sequence = 1; sequence <= 3; sequence++) {
      list.add(skeleton(InterviewPhase.FUNDAMENTALS, sequence));
    }
    for (int sequence = 1; sequence <= 3; sequence++) {
      list.add(skeleton(InterviewPhase.PROJECT_EXPERIENCE, sequence));
    }
    for (int sequence = 1; sequence <= 2; sequence++) {
      list.add(skeleton(InterviewPhase.SCENARIO_TRADEOFF, sequence));
    }
    return list;
  }

  private QuestionSkeletonOutput.Skeleton skeleton(InterviewPhase phase, int sequence) {
    return skeleton(phase, sequence, List.of("核心原理", "失败边界"), "domain.topic");
  }

  private QuestionSkeletonOutput.Skeleton skeleton(
      InterviewPhase phase, int sequence, List<String> focusPoints) {
    return skeleton(phase, sequence, focusPoints, "domain.topic");
  }

  private QuestionSkeletonOutput.Skeleton skeleton(
      InterviewPhase phase, int sequence, List<String> focusPoints, String knowledgePoint) {
    String unique = phase.name() + sequence;
    return new QuestionSkeletonOutput.Skeleton(
        phase,
        sequence,
        "主题" + unique,
        "请详细解释这个知识点的实现原理与边界条件 " + unique,
        focusPoints,
        knowledgePoint,
        List.of("术语甲", "术语乙"),
        "如果线上出现相关故障，你会如何定位并恢复？");
  }
}
