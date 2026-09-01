package interview.pilot.interview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
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
    session.beginEvaluation();
    session.evaluationFailed("INVALID_REPORT");
    session.retryEvaluation();

    assertThat(session.getStatus()).isEqualTo(SessionStatus.EVALUATING);
    assertThat(session.getSafeError()).isNull();
  }

  @Test
  void cardQuotaIsConstrainedByPhase() {
    assertThatThrownBy(() -> InterviewQuestionCardEntity.create(
        1L, InterviewPhase.FUNDAMENTALS, 1, "并发", "这是一个长度足够的基础并发问题文本",
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
