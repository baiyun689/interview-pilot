package interview.pilot.interview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InputMode;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.rag.RagStatus;

class FixedInterviewEntitiesTest {
  @Test
  void lifecycleCanOnlyMoveThroughDomainMethods() {
    var session = InterviewSessionEntity.preparing(
        7L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null);

    assertThat(session.getStatus()).isEqualTo(SessionStatus.PREPARING);
    assertThatThrownBy(session::beginFixedInterview).isInstanceOf(IllegalStateException.class);
    session.preparationReady();
    session.beginFixedInterview();
    assertThat(session.getCurrentTurnNo()).isEqualTo(1);
    assertThat(session.getCurrentMainQuestionNo()).isEqualTo(1);
    session.advanceTo(2, QuestionType.FOLLOW_UP);
    assertThat(session.getCurrentMainQuestionNo()).isEqualTo(1);
    session.advanceTo(3, QuestionType.MAIN);
    assertThat(session.getCurrentMainQuestionNo()).isEqualTo(2);
    session.beginEvaluation();
    session.evaluationFailed("INVALID_REPORT");
    session.retryEvaluation();

    assertThat(session.getStatus()).isEqualTo(SessionStatus.EVALUATING);
    assertThat(session.getSafeError()).isNull();
  }

  @Test
  void sessionDefaultsToTextModeAndVoiceSnapshotIsAnImmutableCreationColumn() {
    var text = InterviewSessionEntity.preparing(
        7L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null);
    assertThat(text.getInterviewMode()).isEqualTo(InterviewMode.TEXT);
    assertThat(text.getVoiceSnapshot()).isNull();

    var voice = InterviewSessionEntity.preparing(
        7L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null,
        InterviewMode.VOICE, "{\"schemaVersion\":1}");
    assertThat(voice.getInterviewMode()).isEqualTo(InterviewMode.VOICE);
    assertThat(voice.getVoiceSnapshot()).isEqualTo("{\"schemaVersion\":1}");

    var nullMode = InterviewSessionEntity.preparing(
        7L, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null,
        null, null);
    assertThat(nullMode.getInterviewMode()).isEqualTo(InterviewMode.TEXT);
  }

  @Test
  void askedTurnDefaultsToTextInputMode() {
    var turn = InterviewTurnEntity.asked(
        1L, 1, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN, 1L, "问题");
    assertThat(turn.getInputMode()).isEqualTo(InputMode.TEXT);
  }

  @Test
  void cardQuotaIsConstrainedByPhase() {
    var fundamentals = InterviewQuestionCardEntity.create(
        1L, InterviewPhase.FUNDAMENTALS, 1, "并发", "这是一个长度足够的基础并发问题文本",
        "[]", GroundingMode.GENERAL, RagStatus.NOT_REQUESTED, "{}", "[]", 1,
        "如果出现复合写操作，你会怎样进一步保证原子性？");
    assertThat(fundamentals.getFollowUpQuota()).isEqualTo(1);

    assertThatThrownBy(() -> InterviewQuestionCardEntity.create(
        1L, InterviewPhase.SELF_INTRODUCTION, 1, "自我介绍", "这是一个长度足够的自我介绍问题文本",
        "[]", GroundingMode.GENERAL, RagStatus.NOT_REQUESTED, "{}", "[]", 1, null))
        .isInstanceOf(IllegalArgumentException.class);

    var project = InterviewQuestionCardEntity.create(
        1L, InterviewPhase.PROJECT_EXPERIENCE, 1, "项目失败窗口",
        "请说明项目写入链路中的失败窗口和补偿机制。", "[]", GroundingMode.GENERAL,
        RagStatus.NOT_REQUESTED, "{}", "[]", 2,
        "如果依赖超时后重复执行，你会如何发现并恢复？");
    assertThat(project.getFollowUpQuota()).isEqualTo(2);
  }
}
