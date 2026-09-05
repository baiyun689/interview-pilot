package interview.pilot.interview.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.idempotency.ProcessingClaim;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.InterviewTurnView;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.domain.AnswerAttemptStatus;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.FixedInterviewFlowPolicy;
import interview.pilot.interview.domain.InputMode;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.AnswerAttemptEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.voice.application.QuestionSpeechTaskCreator;
import interview.pilot.voice.domain.VoiceErrorCodes;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FixedAnswerService {
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final InterviewQuestionCardRepository cards;
  private final AnswerAttemptRepository attempts;
  private final AsyncTaskRepository tasks;
  private final ProcessingClaim coordination;
  private final FollowUpGenerator followUps;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;
  private final VoiceRecordingRepository recordings;
  private final QuestionSpeechTaskCreator questionSpeeches;
  private final AnswerEvaluationProperties evaluationProperties;
  private final FixedInterviewFlowPolicy flow = new FixedInterviewFlowPolicy();

  public FixedAnswerService(
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      InterviewQuestionCardRepository cards,
      AnswerAttemptRepository attempts,
      AsyncTaskRepository tasks,
      ProcessingClaim coordination,
      FollowUpGenerator followUps,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      VoiceRecordingRepository recordings,
      QuestionSpeechTaskCreator questionSpeeches,
      AnswerEvaluationProperties evaluationProperties) {
    this.sessions = sessions;
    this.turns = turns;
    this.cards = cards;
    this.attempts = attempts;
    this.tasks = tasks;
    this.coordination = coordination;
    this.followUps = followUps;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.recordings = recordings;
    this.questionSpeeches = questionSpeeches;
    this.evaluationProperties = evaluationProperties;
  }

  public FixedAnswerClaim claim(
      CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    String coordinationKey = "interview-answer-claim:" + sessionId;
    String token = null;
    boolean coordinationAvailable = true;
    try {
      token = coordination.acquire(coordinationKey, Duration.ofSeconds(15)).orElse(null);
    } catch (RuntimeException ignored) {
      // MySQL remains authoritative when Redis is temporarily unavailable.
      coordinationAvailable = false;
    }
    if (coordinationAvailable && token == null) {
      throw conflict("ANSWER_CLAIM_BUSY", "Another answer claim is being admitted; retry shortly");
    }
    try {
      for (int attempt = 0; ; attempt++) {
        try {
          return transactions.execute(status -> claimInTransaction(user, sessionId, request));
        } catch (DataIntegrityViolationException exception) {
          return transactions.execute(status -> replayOrConflict(user, sessionId, request));
        } catch (OptimisticLockingFailureException exception) {
          // The recording's optimistic lock can lose to a concurrent discard/retry/transcription
          // (the turn's own lock no longer conflicts — it is claimed pessimistically). One
          // retry re-reads the fresh row state and surfaces the accurate 409; the same pattern
          // guards VoiceAnswerServiceImpl.discardRecording.
          if (attempt == 1) {
            throw conflict("ANSWER_CLAIM_CONFLICT", "Answer claim conflicted; retry shortly");
          }
        }
      }
    } finally {
      if (token != null) {
        try {
          coordination.release(coordinationKey, token);
        } catch (RuntimeException ignored) {
          // The durable MySQL claim must survive Redis cleanup failures.
        }
      }
    }
  }

  public FixedAnswerResult process(FixedAnswerClaim claim) {
    if (!claim.owner()) return claim.replay().asReplay();
    Work work = claim.work();
    String nextQuestion = null;
    if (work.next().kind() == NextKind.FOLLOW_UP) {
      try {
        nextQuestion = followUps.generate(
            work.providerId(), work.modelName(), work.parentQuestion(), work.answer(),
            decode(work.focusPointsJson(), Object.class),
            decode(work.ragJson(), RagContextSnapshot.class),
            work.next().phase(), work.difficulty());
      } catch (RuntimeException exception) {
        nextQuestion = work.fallbackFollowUp();
      }
    } else if (work.next().kind() == NextKind.MAIN) {
      nextQuestion = work.next().cardQuestion();
    }
    final String resolvedQuestion = nextQuestion;
    try {
      return transactions.execute(status -> completeInTransaction(work, resolvedQuestion));
    } catch (RuntimeException exception) {
      transactions.executeWithoutResult(status -> failBestEffort(work));
      throw exception;
    }
  }

  private FixedAnswerClaim claimInTransaction(
      CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    long ownerId = owner(user);
    var session = sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(() -> notFound());
    String hash = SubmissionFingerprint.of(request.answer(), request.inputMode(), request.recordingId());
    var existing = attempts.findByRequestId(request.requestId());
    if (existing.isPresent()) return replay(existing.get(), sessionId, request, hash);
    if (session.getStatus() != SessionStatus.INTERVIEWING) {
      throw conflict("INTERVIEW_NOT_ACTIVE", "Interview is not accepting answers");
    }
    var turn = turns.findBySessionIdAndTurnNoForUpdate(session.getId(), session.getCurrentTurnNo())
        .orElseThrow(() -> conflict("CURRENT_TURN_MISSING", "Current interview turn is missing"));
    if (turn.getStatus() != interview.pilot.interview.domain.TurnStatus.ASKED
        && turn.getStatus() != interview.pilot.interview.domain.TurnStatus.FAILED) {
      throw conflict("TURN_ALREADY_CLAIMED", "Current interview turn is already being answered");
    }
    VoiceRecordingEntity recording = null;
    if (request.inputMode() == InputMode.VOICE) {
      if (request.recordingId() == null) {
        throw conflict(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH,
            "inputMode VOICE requires a recordingId");
      }
      if (session.getInterviewMode() != InterviewMode.VOICE) {
        throw conflict(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH,
            "voice recordings require a voice interview");
      }
      recording = requireBindableRecording(session, turn, request.recordingId());
      recording.attach(request.requestId());
    } else {
      // TEXT and VOICE_REALTIME are both text-driven and never bind a recording file.
      // Realtime voice transcribes over a WebSocket, so its answer text arrives directly;
      // it is still only valid inside a VOICE interview session.
      if (request.recordingId() != null) {
        throw conflict(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH,
            "recordingId requires inputMode VOICE");
      }
      if (request.inputMode() == InputMode.VOICE_REALTIME
          && session.getInterviewMode() != InterviewMode.VOICE) {
        throw conflict(VoiceErrorCodes.VOICE_INPUT_MODE_MISMATCH,
            "realtime voice answers require a voice interview");
      }
    }
    var attempt = attempts.save(AnswerAttemptEntity.processing(
        request.requestId(), session.getId(), turn.getId(), hash));
    turn.beginAnswer(request.requestId(), request.answer(), request.inputMode());
    attempts.flush();
    turns.flush();
    if (recording != null) {
      recordings.flush(); // forces the @Version optimistic-lock check inside the transaction
    }
    Work work = buildWork(session, turn, attempt.getId(), request);
    return new FixedAnswerClaim(turn.getTurnNo(), true, null, work);
  }

  /**
   * A recording is bindable only when it is the current user's, belongs to this session and
   * THIS turn, and its transcript is READY. Everything else is hidden as 404 (plan §8.6);
   * READY → ATTACHED is the single allowed state change, so double-binding is impossible.
   */
  private VoiceRecordingEntity requireBindableRecording(
      InterviewSessionEntity session, InterviewTurnEntity turn, UUID recordingId) {
    var recording = recordings.findByRecordingId(recordingId).orElseThrow(this::voiceRecordingNotFound);
    if (!recording.getUserAccountId().equals(session.getUserAccountId())
        || !recording.getSessionId().equals(session.getId())
        || !recording.getTurnId().equals(turn.getId())) {
      throw voiceRecordingNotFound();
    }
    if (recording.getStatus() == VoiceRecordingStatus.ATTACHED) {
      throw conflict(VoiceErrorCodes.VOICE_RECORDING_ALREADY_ATTACHED,
          "The recording is already bound to an answer");
    }
    if (recording.getStatus() != VoiceRecordingStatus.READY) {
      throw conflict(VoiceErrorCodes.VOICE_RECORDING_NOT_READY,
          "The recording transcript is not ready to submit");
    }
    return recording;
  }

  private FixedAnswerClaim replayOrConflict(
      CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    long ownerId = owner(user);
    sessions.findBySessionIdAndUserAccountId(sessionId, ownerId).orElseThrow(this::notFound);
    var attempt = attempts.findByRequestId(request.requestId())
        .orElseThrow(() -> conflict("ANSWER_CLAIM_CONFLICT", "Answer claim conflicted"));
    String hash = SubmissionFingerprint.of(request.answer(), request.inputMode(), request.recordingId());
    return replay(attempt, sessionId, request, hash);
  }

  private FixedAnswerClaim replay(
      AnswerAttemptEntity attempt, UUID sessionId, SubmitAnswerRequest request, String hash) {
    var session = sessions.findBySessionId(sessionId).orElseThrow(this::notFound);
    if (!attempt.getSessionId().equals(session.getId())
        || !attempt.getSubmissionFingerprint().equals(hash)) {
      throw conflict("REQUEST_ID_CONFLICT", "requestId was already used for another answer");
    }
    if (attempt.getStatus() == AnswerAttemptStatus.FAILED) {
      throw conflict("ANSWER_FAILED", "The answer attempt failed; submit with a new requestId");
    }
    if (attempt.getStatus() != AnswerAttemptStatus.COMPLETED) {
      throw conflict("ANSWER_STILL_PROCESSING", "The answer is still processing");
    }
    FixedAnswerResult result = decode(attempt.getResultSnapshot(), FixedAnswerResult.class);
    return new FixedAnswerClaim(result.completedTurnNo(), false, result, null);
  }

  private Work buildWork(
      interview.pilot.interview.infrastructure.InterviewSessionEntity session,
      InterviewTurnEntity current,
      Long attemptId,
      SubmitAnswerRequest request) {
    List<InterviewTurnEntity> allTurns = turns.findAllBySessionIdOrderByTurnNo(session.getId());
    List<InterviewQuestionCardEntity> allCards =
        cards.findAllBySessionIdOrderByPhaseAscPhaseSequenceAsc(session.getId());
    InterviewQuestionCardEntity parent = allCards.stream()
        .filter(card -> card.getId().equals(current.getSourceCardId()))
        .findFirst().orElseThrow();
    Next next = chooseNext(session.getInterviewSize(), current, allTurns, allCards, parent);
    return new Work(
        session.getSessionId(), session.getId(), current.getId(), attemptId,
        request.requestId(), current.getTurnNo(), request.answer(), session.getDifficulty(),
        session.getProviderId(), session.getModelName(), parent.getQuestionText(),
        parent.getFocusPoints(), parent.getRagContextSnapshot(), parent.getFallbackFollowUp(), next);
  }

  private Next chooseNext(
      InterviewSize size,
      InterviewTurnEntity current,
      List<InterviewTurnEntity> allTurns,
      List<InterviewQuestionCardEntity> allCards,
      InterviewQuestionCardEntity parent) {
    InterviewPhase phase = current.getPhase();
    int mainAsked = (int) allTurns.stream().filter(turn ->
        turn.getPhase() == phase && turn.getQuestionType() == QuestionType.MAIN).count();
    int followUpsForCard = (int) allTurns.stream().filter(turn ->
        turn.getQuestionType() == QuestionType.FOLLOW_UP
            && parent.getId().equals(turn.getSourceCardId())).count();

    var decision = flow.next(size, new FixedInterviewFlowPolicy.Progress(
        phase, mainAsked, followUpsForCard, parent.getFollowUpQuota()));
    if (decision instanceof FixedInterviewFlowPolicy.FollowUp followUp) {
      return Next.followUp(followUp.phase(), parent.getId());
    }
    if (decision instanceof FixedInterviewFlowPolicy.MainQuestion main) {
      return Next.main(
          main.phase(), card(allCards, main.phase(), main.sequence()));
    }
    return Next.end();
  }

  private InterviewQuestionCardEntity card(
      List<InterviewQuestionCardEntity> cards,
      InterviewPhase phase,
      int sequence) {
    return cards.stream().filter(card ->
        card.getPhase() == phase && card.getPhaseSequence() == sequence)
        .findFirst().orElseThrow(() -> new IllegalStateException("Required question card is missing"));
  }

  private FixedAnswerResult completeInTransaction(Work work, String nextQuestion) {
    var session = sessions.findBySessionId(work.sessionId()).orElseThrow();
    var current = turns.findById(work.turnId()).orElseThrow();
    var attempt = attempts.findById(work.attemptId()).orElseThrow();
    if (current.getStatus() != interview.pilot.interview.domain.TurnStatus.PROCESSING
        || !work.requestId().equals(current.getRequestId())
        || attempt.getStatus() != AnswerAttemptStatus.PROCESSING) {
      throw conflict("ANSWER_FINALIZATION_CONFLICT", "Answer finalization conflicted");
    }
    current.completeAnswer();
    scheduleAnswerEvaluation(session, current, work.requestId());
    InterviewTurnEntity nextTurn = null;
    if (work.next().kind() == NextKind.END) {
      session.beginEvaluation();
      String key = "interview:" + session.getSessionId();
      if (tasks.findByTaskTypeAndBizKey(AsyncTaskType.INTERVIEW_EVALUATION, key).isEmpty()) {
        tasks.save(AsyncTaskEntity.pending(
            session.getUserAccountId(), AsyncTaskType.INTERVIEW_EVALUATION, key,
            encode(java.util.Map.of("sessionId", session.getSessionId()))));
      }
    } else {
      nextTurn = InterviewTurnEntity.asked(
          session.getId(), current.getTurnNo() + 1, work.next().phase(),
          work.next().kind() == NextKind.MAIN ? QuestionType.MAIN : QuestionType.FOLLOW_UP,
          work.next().sourceCardId(), nextQuestion);
      turns.save(nextTurn);
      // Same transaction as the turn (plan §11): VOICE sessions with TTS configured get a
      // question_speech row and its unique synthesis task for every next turn; the speech
      // status never influences the answer flow (TTS failure is a degradable capability).
      questionSpeeches.createForTurn(session, nextTurn);
      session.advanceTo(nextTurn.getTurnNo(), nextTurn.getQuestionType());
    }
    FixedAnswerResult result = new FixedAnswerResult(
        session.getSessionId(), work.requestId(), current.getTurnNo(), session.getStatus(),
        nextTurn == null ? null : view(nextTurn), false);
    attempt.complete(encode(result));
    return result;
  }

  /**
   * Outbox in the same transaction that completes the answer (plan §3.6): a formal turn gets a
   * PENDING ANSWER_EVALUATION task whose bizKey is unique per session+turn; the self-introduction
   * turn is marked SKIPPED. The evaluation itself runs later off the answer critical path.
   */
  private void scheduleAnswerEvaluation(
      InterviewSessionEntity session, InterviewTurnEntity turn, UUID requestId) {
    if (turn.getPhase() == InterviewPhase.SELF_INTRODUCTION) {
      turn.skipEvaluation();
      return;
    }
    if (!evaluationProperties.isEnabled()) {
      return; // turn stays NOT_REQUIRED and the report uses the legacy raw-text path
    }
    turn.markEvaluationPending();
    String key = interview.pilot.async.policy.AnswerEvaluationRetryPolicy.BIZ_KEY_PREFIX
        + session.getSessionId() + ":" + turn.getTurnNo();
    if (tasks.findByTaskTypeAndBizKey(AsyncTaskType.ANSWER_EVALUATION, key).isEmpty()) {
      tasks.save(AsyncTaskEntity.pending(
          session.getUserAccountId(), AsyncTaskType.ANSWER_EVALUATION, key,
          encode(java.util.Map.of(
              "sessionId", session.getSessionId(),
              "turnNo", turn.getTurnNo(),
              "requestId", requestId))));
    }
  }

  private void failBestEffort(Work work) {
    attempts.findById(work.attemptId()).ifPresent(attempt -> {
      if (attempt.getStatus() == AnswerAttemptStatus.PROCESSING) attempt.fail("ANSWER_PROCESSING_FAILED");
    });
    turns.findById(work.turnId()).ifPresent(turn -> {
      if (turn.getStatus() == interview.pilot.interview.domain.TurnStatus.PROCESSING) {
        turn.failAnswer("ANSWER_PROCESSING_FAILED");
      }
    });
  }

  private InterviewTurnView view(InterviewTurnEntity turn) {
    return new InterviewTurnView(
        turn.getTurnNo(), turn.getPhase(), turn.getQuestionType(), turn.getQuestionText(),
        turn.getStatus(), turn.getAnswerText(), turn.getAskedAt(), turn.getAnsweredAt());
  }

  private long owner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private BusinessException notFound() {
    return new BusinessException("INTERVIEW_NOT_FOUND", "Interview not found", HttpStatus.NOT_FOUND);
  }

  private BusinessException voiceRecordingNotFound() {
    return new BusinessException(VoiceErrorCodes.VOICE_RECORDING_NOT_FOUND,
        "Voice recording not found", HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private <T> T decode(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored interview snapshot is invalid", exception);
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Interview result could not be encoded", exception);
    }
  }

  enum NextKind { MAIN, FOLLOW_UP, END }

  record Next(
      NextKind kind, InterviewPhase phase, Long sourceCardId, String cardQuestion) {
    static Next main(InterviewPhase phase, InterviewQuestionCardEntity card) {
      return new Next(NextKind.MAIN, phase, card.getId(), card.getQuestionText());
    }
    static Next followUp(InterviewPhase phase, Long cardId) {
      return new Next(NextKind.FOLLOW_UP, phase, cardId, null);
    }
    static Next end() { return new Next(NextKind.END, null, null, null); }
  }

  public record Work(
      UUID sessionId, Long sessionDatabaseId, Long turnId, Long attemptId,
      UUID requestId, int turnNo, String answer, Difficulty difficulty,
      String providerId, String modelName, String parentQuestion,
      String focusPointsJson, String ragJson, String fallbackFollowUp, Next next) { }
}
