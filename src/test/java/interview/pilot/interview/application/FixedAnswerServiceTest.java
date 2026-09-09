package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import tools.jackson.databind.ObjectMapper;

class FixedAnswerServiceTest {
  @Test void lateFollowUpResultLeavesAdmittedAnswerForDeadlineSweep() {
    var sessions=mock(InterviewSessionRepository.class);var turns=mock(InterviewTurnRepository.class);
    var attempts=mock(AnswerAttemptRepository.class);
    var session=interview.pilot.interview.infrastructure.InterviewSessionEntity.preparing(1L,null,
        interview.pilot.interview.domain.Difficulty.MEDIUM,interview.pilot.interview.domain.InterviewSize.STANDARD,
        interview.pilot.interview.domain.JobSourceType.CUSTOM,"Job","test","model","{}",null);
    session.bindInvitation(1L,java.time.Instant.now().minusSeconds(1),2);
    session.preparationReady();session.beginFixedInterview();
    org.springframework.test.util.ReflectionTestUtils.setField(session,"id",1L);
    when(sessions.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));
    when(sessions.findByIdForUpdate(1L)).thenReturn(Optional.of(session));
    var service=new FixedAnswerService(sessions,turns,mock(InterviewQuestionCardRepository.class),attempts,
        mock(AsyncTaskRepository.class),mock(ProcessingClaim.class),mock(FollowUpGenerator.class),new ObjectMapper(),
        mock(PlatformTransactionManager.class),mock(interview.pilot.voice.infrastructure.VoiceRecordingRepository.class),
        mock(interview.pilot.voice.application.QuestionSpeechTaskCreator.class),new AnswerEvaluationProperties());
    var work=new FixedAnswerService.Work(session.getSessionId(),1L,1L,1L,UUID.randomUUID(),1,"按时提交的回答",
        session.getDifficulty(),"test","model","问题","[]","{}","追问",FixedAnswerService.Next.end());
    assertThatThrownBy(()->service.process(new FixedAnswerClaim(1,true,null,work))).isInstanceOf(BusinessException.class);
    org.mockito.Mockito.verifyNoInteractions(turns,attempts);
  }
  @Test
  void activeRedisAdmissionClaimFastRejectsAConcurrentAnswer() {
    ProcessingClaim coordination = mock(ProcessingClaim.class);
    when(coordination.acquire(anyString(), any())).thenReturn(Optional.empty());
    var service = new FixedAnswerService(
        mock(InterviewSessionRepository.class), mock(InterviewTurnRepository.class),
        mock(InterviewQuestionCardRepository.class), mock(AnswerAttemptRepository.class),
        mock(AsyncTaskRepository.class), coordination, mock(FollowUpGenerator.class),
        new ObjectMapper(), mock(PlatformTransactionManager.class),
        mock(interview.pilot.voice.infrastructure.VoiceRecordingRepository.class),
        mock(interview.pilot.voice.application.QuestionSpeechTaskCreator.class),
        new AnswerEvaluationProperties());

    assertThatThrownBy(() -> service.claim(
        new CurrentUser(1L, UUID.randomUUID(), "user@example.com", "User"),
        UUID.randomUUID(), new SubmitAnswerRequest(UUID.randomUUID(), "有效回答")))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> org.assertj.core.api.Assertions.assertThat(error.code())
                .isEqualTo("ANSWER_CLAIM_BUSY"));
  }
}
