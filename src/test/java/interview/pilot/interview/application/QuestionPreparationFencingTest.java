package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import interview.pilot.async.domain.*;
import interview.pilot.async.infrastructure.*;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import tools.jackson.databind.ObjectMapper;

class QuestionPreparationFencingTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final InterviewBriefSnapshot brief = new InterviewBriefSnapshot(
      JobSourceType.CUSTOM, null, null, "Backend", "Java backend", null, null,
      Difficulty.MEDIUM, InterviewSize.STANDARD, "test", "model", null, 2);
  private final InterviewSessionEntity session = InterviewSessionEntity.preparing(
      7L, null, Difficulty.MEDIUM, InterviewSize.STANDARD, JobSourceType.CUSTOM,
      "Backend", "test", "model", mapper.writeValueAsString(brief), null);
  private final AsyncTaskEntity task = AsyncTaskEntity.pending(
      7L, AsyncTaskType.INTERVIEW_QUESTION_PREPARATION, "interview:" + session.getSessionId(), "{}");
  private final TaskMessage message = new TaskMessage(java.util.UUID.randomUUID(), task.getTaskType(), task.getBizKey());
  private final QuestionSkeletonGenerator skeletons = mock(QuestionSkeletonGenerator.class);
  private final RubricGenerator rubrics = mock(RubricGenerator.class);
  private final InterviewQuestionCardRepository cards = mock(InterviewQuestionCardRepository.class);
  private final QuestionPreparationHandler handler;

  QuestionPreparationFencingTest() {
    org.springframework.test.util.ReflectionTestUtils.setField(session, "id", 1L);
    task.setTaskId(message.taskId());
    var tasks = mock(AsyncTaskRepository.class);
    var sessions = mock(InterviewSessionRepository.class);
    when(tasks.findByTaskId(task.getTaskId())).thenReturn(Optional.of(task));
    when(sessions.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));
    handler = new QuestionPreparationHandler(tasks, sessions, cards, skeletons,
        mock(QuestionRagRetriever.class), rubrics, mock(FollowUpQuotaAllocator.class), mapper,
        mock(PlatformTransactionManager.class), java.time.Duration.ofMinutes(11));
  }

  @Test void inspectionMustNotClaimExecution() {
    handler.inspect(message);
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.PENDING);
    assertThat(task.getAttemptCount()).isZero();
  }

  @Test void messageFromAnEarlierEpochCannotStartWork() {
    task.setExecutionEpoch(1);
    assertThat(handler.prepare(message)).isEqualTo(QuestionPreparationHandler.Outcome.STALE);
    verifyNoInteractions(skeletons, rubrics, cards);
  }
}
