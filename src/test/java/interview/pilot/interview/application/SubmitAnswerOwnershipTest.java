package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.domain.AnswerAttemptStatus;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;
import tools.jackson.databind.ObjectMapper;

class SubmitAnswerOwnershipTest {
  @Test
  void corruptedClaimForAnotherSessionsTurnFailsBeforeEvaluatorOrWrites() {
    InterviewTurnClaimer claimer = mock(InterviewTurnClaimer.class);
    AnswerEvaluator evaluator = mock(AnswerEvaluator.class);
    InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    InterviewTurnRepository turns = mock(InterviewTurnRepository.class);
    AnswerAttemptRepository attempts = mock(AnswerAttemptRepository.class);
    PlatformTransactionManager transactions = transactionManager();
    CurrentUser user = new CurrentUser(7L, UUID.randomUUID(), "owner@example.com", "Owner");
    UUID publicSessionId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    InterviewSessionEntity session = InterviewSessionEntity.create(
        7L, 1L, 2L, Difficulty.MEDIUM, 1, "provider", "model", "{}");
    session.setId(10L);
    InterviewTurnEntity foreignTurn = mock(InterviewTurnEntity.class);
    when(foreignTurn.getSessionId()).thenReturn(99L);
    when(foreignTurn.getStatus()).thenReturn(TurnStatus.PROCESSING);
    when(foreignTurn.getRequestId()).thenReturn(requestId);
    when(foreignTurn.getVersion()).thenReturn(0L);
    when(foreignTurn.getAnswerText()).thenReturn("answer");
    when(sessions.findBySessionIdAndUserAccountId(publicSessionId, 7L))
        .thenReturn(Optional.of(session));
    when(sessions.findByIdAndUserAccountId(10L, 7L)).thenReturn(Optional.of(session));
    when(turns.findById(20L)).thenReturn(Optional.of(foreignTurn));

    SubmitAnswerService service = new SubmitAnswerService(
        claimer, evaluator, mock(QuestionGenerator.class), sessions, attempts, turns,
        mock(JobProfileRepository.class), mock(ResumeRepository.class), new ObjectMapper(),
        mock(StoredAnswerResultCodec.class), mock(jakarta.validation.Validator.class),
        new InterviewProcessingSla(new interview.pilot.ai.provider.AiProviderProperties("", java.util.Map.of(), 1),
            java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1)),
        mock(InterviewCompletionService.class), transactions, mock(AiMetrics.class));
    InterviewTurnClaim claim = new InterviewTurnClaim(
        InterviewTurnClaim.State.OWNER, 10L, 20L, 30L, requestId,
        AnswerFingerprint.sha256("answer"),
        1, 0, 0, AnswerAttemptStatus.PROCESSING, null, null);

    assertThatThrownBy(() -> service.processClaim(
        user, publicSessionId, new SubmitAnswerRequest(requestId, "answer"), claim))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("ANSWER_OWNERSHIP_LOST"));
    verify(evaluator, never()).evaluate(any());
    verify(turns, never()).save(any());
    verify(attempts, never()).save(any());
  }

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    try {
      when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    } catch (org.springframework.transaction.TransactionException exception) {
      throw new AssertionError(exception);
    }
    return manager;
  }
}
