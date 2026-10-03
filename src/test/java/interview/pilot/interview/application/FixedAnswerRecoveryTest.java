package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.domain.*;
import interview.pilot.interview.infrastructure.*;
import interview.pilot.voice.application.QuestionSpeechTaskCreator;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import tools.jackson.databind.ObjectMapper;

class FixedAnswerRecoveryTest {
  private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
  private final InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
  private final AnswerAttemptRepository attempts = mock(AnswerAttemptRepository.class);
  private final InterviewQuestionCardRepository cards = mock(InterviewQuestionCardRepository.class);
  private final FollowUpGenerator followUps = mock(FollowUpGenerator.class);
  private final InterviewSessionEntity session = InterviewSessionEntity.preparing(1L, null,
      Difficulty.MEDIUM, InterviewSize.STANDARD, JobSourceType.CUSTOM, "Job", "test", "model", "{}", null);
  private final UUID requestId = UUID.randomUUID();
  private final InterviewTurnEntity turn = InterviewTurnEntity.asked(
      1L, 1, InterviewPhase.SELF_INTRODUCTION, QuestionType.SELF_INTRODUCTION, 9L, "Introduce yourself");
  private final AnswerAttemptEntity attempt = AnswerAttemptEntity.processing(requestId, 1L, 2L, "fingerprint");
  private final FixedAnswerService service = new FixedAnswerService(sessions, turns, cards, attempts,
      mock(AsyncTaskRepository.class), followUps, new ObjectMapper(),
      mock(PlatformTransactionManager.class), mock(VoiceRecordingRepository.class),
      mock(QuestionSpeechTaskCreator.class), new AnswerEvaluationProperties());

  FixedAnswerRecoveryTest() {
    ReflectionTestUtils.setField(session, "id", 1L);
    ReflectionTestUtils.setField(turn, "id", 2L);
    ReflectionTestUtils.setField(attempt, "id", 3L);
    ReflectionTestUtils.setField(attempt, "createdAt", Instant.now().minusSeconds(3600));
    session.preparationReady();
    session.beginFixedInterview();
    turn.beginAnswer(requestId, "Saved answer", InputMode.TEXT);
    when(sessions.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));
    when(sessions.findByIdForUpdate(1L)).thenReturn(Optional.of(session));
    when(turns.findById(2L)).thenReturn(Optional.of(turn));
    when(turns.findBySessionIdAndTurnNoForUpdate(1L, 1)).thenReturn(Optional.of(turn));
    when(attempts.findById(3L)).thenReturn(Optional.of(attempt));
  }

  @Test void oldWorkerFailureMustNotFailANewerAnswerOnTheSameTurn() {
    var work = new FixedAnswerService.Work(session.getSessionId(), 1L, 2L, 3L, requestId, 1,
        "Old answer", Difficulty.MEDIUM, "test", "model", "Question", "[]", "{}", "Fallback",
        FixedAnswerService.Next.end());
    attempt.fail("EXPIRED");
    turn.failAnswer("EXPIRED");
    UUID newRequestId = UUID.randomUUID();
    turn.beginAnswer(newRequestId, "New answer", InputMode.TEXT);
    assertThatThrownBy(() -> service.process(new FixedAnswerClaim(1, true, null, work)))
        .isInstanceOf(interview.pilot.common.exception.BusinessException.class);
    assertThat(turn.getStatus()).isEqualTo(TurnStatus.PROCESSING);
    assertThat(turn.getRequestId()).isEqualTo(newRequestId);
  }

  @Test void recoveryPreservesTheAnswerAndAtomicallyStoresTheNextQuestionAndReplay() {
    seedCards();
    assertThat(service.recoverExpired(3L, 1L, Instant.now().minusSeconds(60))).isTrue();
    assertThat(turn.getStatus()).isEqualTo(TurnStatus.COMPLETED);
    assertThat(turn.getAnswerText()).isEqualTo("Saved answer");
    assertThat(session.getCurrentTurnNo()).isEqualTo(2);
    assertThat(attempt.getStatus()).isEqualTo(AnswerAttemptStatus.COMPLETED);
    assertThat(attempt.getResultSnapshot()).contains("Next main question");
    verify(followUps, never()).generate(any(), any(), any(), any(), any(), any(), any(), any());
    // Another node scanning the same candidate cannot create a second next turn.
    assertThat(service.recoverExpired(3L, 1L, Instant.now().minusSeconds(60))).isFalse();
    verify(turns, times(1)).save(any());
  }

  @Test void aRecentAttemptIsNotTakenOver() {
    ReflectionTestUtils.setField(attempt, "createdAt", Instant.now());
    assertThat(service.recoverExpired(3L, 1L, Instant.now().minusSeconds(60))).isFalse();
    assertThat(attempt.getStatus()).isEqualTo(AnswerAttemptStatus.PROCESSING);
    verifyNoInteractions(cards, followUps);
  }

  @Test void recoveryUsesTheFrozenFallbackAndNeverCallsTheModel() {
    seedCards();
    ReflectionTestUtils.setField(turn, "phase", InterviewPhase.FUNDAMENTALS);
    ReflectionTestUtils.setField(turn, "questionType", QuestionType.MAIN);
    ReflectionTestUtils.setField(turn, "sourceCardId", 10L);
    assertThat(service.recoverExpired(3L, 1L, Instant.now().minusSeconds(60))).isTrue();
    assertThat(attempt.getResultSnapshot()).contains("Frozen fallback");
    verifyNoInteractions(followUps);
  }

  @Test void expiredEnterpriseAnswerIsLeftForTheDeadlineSweep() {
    ReflectionTestUtils.setField(session, "id", null);
    ReflectionTestUtils.setField(session, "status", SessionStatus.PREPARING);
    session.bindInvitation(4L, Instant.now().minusSeconds(1), 2);
    ReflectionTestUtils.setField(session, "id", 1L);
    ReflectionTestUtils.setField(session, "status", SessionStatus.INTERVIEWING);
    assertThat(service.recoverExpired(3L, 1L, Instant.now().minusSeconds(60))).isFalse();
    assertThat(attempt.getStatus()).isEqualTo(AnswerAttemptStatus.PROCESSING);
    verifyNoInteractions(cards, followUps);
  }

  private void seedCards() {
    var intro = InterviewQuestionCardEntity.create(1L, InterviewPhase.SELF_INTRODUCTION,
        1, "Intro", "Introduce yourself", "[]", GroundingMode.GENERAL,
        interview.pilot.interview.rag.RagStatus.DISABLED, "{}", "[]", 0, null);
    var main = InterviewQuestionCardEntity.create(1L, InterviewPhase.FUNDAMENTALS,
        1, "Concurrency", "Next main question", "[]", GroundingMode.GENERAL,
        interview.pilot.interview.rag.RagStatus.DISABLED, "{}", "[]", 1, "Frozen fallback");
    ReflectionTestUtils.setField(intro, "id", 9L);
    ReflectionTestUtils.setField(main, "id", 10L);
    when(cards.findAllBySessionIdOrderByPhaseAscPhaseSequenceAsc(1L)).thenReturn(List.of(intro, main));
    when(turns.findAllBySessionIdOrderByTurnNo(1L)).thenReturn(List.of(turn));
  }
}
