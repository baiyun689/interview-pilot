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
