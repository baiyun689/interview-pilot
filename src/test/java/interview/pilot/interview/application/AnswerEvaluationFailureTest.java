package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import interview.pilot.async.domain.*;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.*;
import interview.pilot.async.messaging.*;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.interview.rag.RagContextSnapshot;
import tools.jackson.databind.ObjectMapper;

/** Regression for the committed begin transaction followed by a failed model call. */
class AnswerEvaluationFailureTest {
  @Test
  void exhaustedModelFailureShouldPersistDeadWithoutGenerationMismatch() {
    var tasks = mock(AsyncTaskRepository.class);
    var sessions = mock(InterviewSessionRepository.class);
    var turns = mock(InterviewTurnRepository.class);
    var cards = mock(InterviewQuestionCardRepository.class);
    var evaluator = mock(AnswerEvaluator.class);
    var tx = mock(PlatformTransactionManager.class);
    when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    var mapper = new ObjectMapper();
    UUID sessionId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    String key = "answer-eval:" + sessionId + ":1";
    var task = AsyncTaskEntity.pending(7L, AsyncTaskType.ANSWER_EVALUATION, key, "{}");
    task.setTaskId(taskId);
    task.setStatus(AsyncTaskStatus.PUBLISHED);
    task.setAttemptCount(3);
    var session = mock(InterviewSessionEntity.class);
    when(session.getId()).thenReturn(11L);
    var turn = mock(InterviewTurnEntity.class);
    when(turn.getEvalStatus()).thenReturn(EvalStatus.PENDING);
    when(turn.getSourceCardId()).thenReturn(22L);
    when(turn.getQuestionText()).thenReturn("Explain idempotency");
    when(turn.getAnswerText()).thenReturn("Use a durable unique business key");
    var card = mock(InterviewQuestionCardEntity.class);
    when(card.getGroundingMode()).thenReturn(GroundingMode.GENERAL);
    when(card.getFocusPoints()).thenReturn("[]");
    when(card.getRagContextSnapshot()).thenReturn(mapper.writeValueAsString(RagContextSnapshot.notConfigured()));
    when(tasks.findByTaskId(taskId)).thenReturn(Optional.of(task));
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 7L)).thenReturn(Optional.of(session));
    when(turns.findBySessionIdAndTurnNo(11L, 1)).thenReturn(Optional.of(turn));
    when(cards.findById(22L)).thenReturn(Optional.of(card));
    when(evaluator.evaluate(any())).thenThrow(new IllegalStateException("injected model failure"));
    var handler = new AnswerEvaluationHandler(tasks, sessions, turns, cards, evaluator, mapper, tx);
    var claims = mock(ProcessingClaim.class);
    when(claims.acquire(eq(key), any())).thenReturn(Optional.of("owner"));
    var publisher = mock(TaskMessagePublisher.class);
    var listener = new AnswerEvaluationListener(handler, claims, new TaskRetryPolicy(publisher));
    var props = new MessageProperties();
    props.setHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER, 3);
    var failure = catchThrowable(() -> listener.receive(
        new TaskMessage(taskId, AsyncTaskType.ANSWER_EVALUATION, key, 0),
        new Message(new byte[0], props)));
    verify(evaluator).evaluate(any());
    assertThat(task.getAttemptCount()).isEqualTo(4);
    assertThat(failure).as("last failed attempt should be persisted as DEAD").isNull();
    assertThat(task.getStatus()).isEqualTo(AsyncTaskStatus.DEAD);
    verify(turn).failEvaluation();
  }
}

