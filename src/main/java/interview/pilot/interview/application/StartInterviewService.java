package interview.pilot.interview.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.InterviewTurnView;
import interview.pilot.interview.api.StartInterviewResponse;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.application.QuestionSpeechTaskCreator;

@Service
public class StartInterviewService {
  private final InterviewSessionRepository sessions;
  private final InterviewQuestionCardRepository cards;
  private final InterviewTurnRepository turns;
  private final QuestionSpeechTaskCreator questionSpeeches;

  public StartInterviewService(
      InterviewSessionRepository sessions,
      InterviewQuestionCardRepository cards,
      InterviewTurnRepository turns,
      QuestionSpeechTaskCreator questionSpeeches) {
    this.sessions = sessions;
    this.cards = cards;
    this.turns = turns;
    this.questionSpeeches = questionSpeeches;
  }

  @Transactional
  public StartInterviewResponse start(CurrentUser user, UUID sessionId) {
    Long ownerId = user == null ? null : user.databaseId();
    if (ownerId == null) throw new IllegalArgumentException("Authenticated user is required");
    var session = sessions.findForStart(sessionId, ownerId)
        .orElseThrow(() -> new BusinessException(
            "INTERVIEW_NOT_FOUND", "Interview not found", HttpStatus.NOT_FOUND));
    var existing = turns.findBySessionIdAndTurnNo(session.getId(), 1);
    if (session.getStatus() == SessionStatus.INTERVIEWING && existing.isPresent()) {
      return response(sessionId, existing.get(), true);
    }
    if (session.getStatus() != SessionStatus.READY) {
      throw new BusinessException(
          "INTERVIEW_NOT_READY",
          "Interview can only start after question preparation is ready",
          HttpStatus.CONFLICT);
    }
    var card = cards.findBySessionIdAndPhaseAndPhaseSequence(
        session.getId(), InterviewPhase.SELF_INTRODUCTION, 1)
        .orElseThrow(() -> new IllegalStateException("Self introduction card is missing"));
    session.beginFixedInterview();
    var first = turns.save(InterviewTurnEntity.asked(
        session.getId(), 1, InterviewPhase.SELF_INTRODUCTION,
        QuestionType.SELF_INTRODUCTION, card.getId(), card.getQuestionText()));
    // Same transaction (plan §11): VOICE sessions with TTS configured get a question_speech
    // row and its unique synthesis task; anything else is a no-op.
    questionSpeeches.createForTurn(session, first);
    return response(sessionId, first, false);
  }

  private StartInterviewResponse response(
      UUID sessionId, InterviewTurnEntity turn, boolean replay) {
    return new StartInterviewResponse(
        sessionId, SessionStatus.INTERVIEWING,
        new InterviewTurnView(
            turn.getTurnNo(), turn.getPhase(), turn.getQuestionType(),
            turn.getQuestionText(), turn.getStatus(), turn.getAnswerText(),
            turn.getAskedAt(), turn.getAnsweredAt()),
        replay);
  }
}
