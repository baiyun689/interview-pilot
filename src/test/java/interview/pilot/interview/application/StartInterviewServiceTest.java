package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;

class StartInterviewServiceTest {
  @Test
  void locksTheSessionAndCreatesTheFixedSelfIntroduction() {
    var sessions = mock(InterviewSessionRepository.class);
    var cards = mock(InterviewQuestionCardRepository.class);
    var turns = mock(InterviewTurnRepository.class);
    var service = new StartInterviewService(sessions, cards, turns);
    UUID sessionId = UUID.randomUUID();
    var session = mock(InterviewSessionEntity.class);
    var card = mock(InterviewQuestionCardEntity.class);
    when(session.getId()).thenReturn(7L);
    when(session.getStatus()).thenReturn(SessionStatus.READY);
    when(card.getId()).thenReturn(9L);
    when(card.getQuestionText()).thenReturn(QuestionPreparationHandler.SELF_INTRODUCTION);
    when(sessions.findForStart(sessionId, 3L)).thenReturn(Optional.of(session));
    when(turns.findBySessionIdAndTurnNo(7L, 1)).thenReturn(Optional.empty());
    when(cards.findBySessionIdAndPhaseAndPhaseSequence(
        7L, InterviewPhase.SELF_INTRODUCTION, 1)).thenReturn(Optional.of(card));
    when(turns.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

    var response = service.start(new CurrentUser(
        3L, UUID.randomUUID(), "user@example.com", "User"), sessionId);

    assertThat(response.currentTurn().questionType()).isEqualTo(QuestionType.SELF_INTRODUCTION);
    assertThat(response.currentTurn().question()).isEqualTo(QuestionPreparationHandler.SELF_INTRODUCTION);
    assertThat(response.idempotentReplay()).isFalse();
    verify(session).beginFixedInterview();
    verify(sessions).findForStart(sessionId, 3L);
  }

  @Test
  void repeatedStartReturnsThePersistedFirstTurn() {
    var sessions = mock(InterviewSessionRepository.class);
    var turns = mock(InterviewTurnRepository.class);
    var service = new StartInterviewService(
        sessions, mock(InterviewQuestionCardRepository.class), turns);
    UUID sessionId = UUID.randomUUID();
    var session = mock(InterviewSessionEntity.class);
    var first = InterviewTurnEntity.asked(
        7L, 1, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, 9L, QuestionPreparationHandler.SELF_INTRODUCTION);
    when(session.getId()).thenReturn(7L);
    when(session.getStatus()).thenReturn(SessionStatus.INTERVIEWING);
    when(sessions.findForStart(sessionId, 3L)).thenReturn(Optional.of(session));
    when(turns.findBySessionIdAndTurnNo(7L, 1)).thenReturn(Optional.of(first));

    var response = service.start(new CurrentUser(
        3L, UUID.randomUUID(), "user@example.com", "User"), sessionId);

    assertThat(response.idempotentReplay()).isTrue();
    assertThat(response.currentTurn().turnNo()).isEqualTo(1);
  }
}
