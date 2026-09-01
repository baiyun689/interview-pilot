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
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.AnswerAttemptEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagContextSnapshot;
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

  public FixedAnswerService(
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      InterviewQuestionCardRepository cards,
      AnswerAttemptRepository attempts,
      AsyncTaskRepository tasks,
      ProcessingClaim coordination,
      FollowUpGenerator followUps,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this.sessions = sessions;
    this.turns = turns;
    this.cards = cards;
    this.attempts = attempts;
    this.tasks = tasks;
    this.coordination = coordination;
    this.followUps = followUps;
    this.objectMapper = objectMapper;
    this.transactions = new TransactionTemplate(transactionManager);
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
      try {
        return transactions.execute(status -> claimInTransaction(user, sessionId, request));
      } catch (DataIntegrityViolationException exception) {
        return transactions.execute(status -> replayOrConflict(user, sessionId, request));
      } catch (OptimisticLockingFailureException exception) {
        throw conflict("TURN_ALREADY_CLAIMED", "Current interview turn is already being answered");
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
    String hash = AnswerFingerprint.sha256(request.answer());
    var existing = attempts.findByRequestId(request.requestId());
    if (existing.isPresent()) return replay(existing.get(), sessionId, request, hash);
    if (session.getStatus() != SessionStatus.INTERVIEWING) {
      throw conflict("INTERVIEW_NOT_ACTIVE", "Interview is not accepting answers");
    }
    var turn = turns.findBySessionIdAndTurnNo(session.getId(), session.getCurrentTurnNo())
        .orElseThrow(() -> conflict("CURRENT_TURN_MISSING", "Current interview turn is missing"));
    if (turn.getStatus() != interview.pilot.interview.domain.TurnStatus.ASKED
        && turn.getStatus() != interview.pilot.interview.domain.TurnStatus.FAILED) {
      throw conflict("TURN_ALREADY_CLAIMED", "Current interview turn is already being answered");
    }
    var attempt = attempts.save(AnswerAttemptEntity.processing(
        request.requestId(), session.getId(), turn.getId(), hash));
    turn.beginAnswer(request.requestId(), request.answer());
    attempts.flush();
    turns.flush();
    Work work = buildWork(session, turn, attempt.getId(), request);
    return new FixedAnswerClaim(turn.getTurnNo(), true, null, work);
  }

  private FixedAnswerClaim replayOrConflict(
      CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    long ownerId = owner(user);
    sessions.findBySessionIdAndUserAccountId(sessionId, ownerId).orElseThrow(this::notFound);
    var attempt = attempts.findByRequestId(request.requestId())
        .orElseThrow(() -> conflict("ANSWER_CLAIM_CONFLICT", "Answer claim conflicted"));
    String hash = AnswerFingerprint.sha256(request.answer());
    return replay(attempt, sessionId, request, hash);
  }

  private FixedAnswerClaim replay(
      AnswerAttemptEntity attempt, UUID sessionId, SubmitAnswerRequest request, String hash) {
    var session = sessions.findBySessionId(sessionId).orElseThrow(this::notFound);
    if (!attempt.getSessionId().equals(session.getId()) || !attempt.getAnswerHash().equals(hash)) {
      throw conflict("REQUEST_ID_CONFLICT", "requestId was already used for another answer");
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
    if (current.getTurnNo() >= size.totalTurns()) return Next.end();
    InterviewPhase phase = current.getPhase();
    int phaseTurnsUsed = (int) allTurns.stream().filter(turn -> turn.getPhase() == phase).count();
    int mainAsked = (int) allTurns.stream().filter(turn ->
        turn.getPhase() == phase && turn.getQuestionType() == QuestionType.MAIN).count();
    int followUpsForCard = (int) allTurns.stream().filter(turn ->
        turn.getQuestionType() == QuestionType.FOLLOW_UP
            && parent.getId().equals(turn.getSourceCardId())).count();

    if (phase.allowsFollowUp()
        && parent.getFollowUpQuota() > followUpsForCard
        && size.canAskFollowUp(phase, mainAsked, phaseTurnsUsed)) {
      return Next.followUp(phase, parent.getId());
    }
    if (phaseTurnsUsed < size.turnBudget(phase)) {
      var nextCard = card(allCards, phase, mainAsked + 1);
      return Next.main(phase, nextCard);
    }
    InterviewPhase nextPhase = nextPhase(phase);
    var nextCard = card(allCards, nextPhase, 1);
    return Next.main(nextPhase, nextCard);
  }

  private InterviewQuestionCardEntity card(
      List<InterviewQuestionCardEntity> cards,
      InterviewPhase phase,
      int sequence) {
    return cards.stream().filter(card ->
        card.getPhase() == phase && card.getPhaseSequence() == sequence)
        .findFirst().orElseThrow(() -> new IllegalStateException("Required question card is missing"));
  }

  private InterviewPhase nextPhase(InterviewPhase phase) {
    return switch (phase) {
      case SELF_INTRODUCTION -> InterviewPhase.FUNDAMENTALS;
      case FUNDAMENTALS -> InterviewPhase.PROJECT_EXPERIENCE;
      case PROJECT_EXPERIENCE -> InterviewPhase.SCENARIO_TRADEOFF;
      case SCENARIO_TRADEOFF -> throw new IllegalStateException("No phase follows scenario");
    };
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
      session.advanceTo(nextTurn.getTurnNo());
    }
    FixedAnswerResult result = new FixedAnswerResult(
        session.getSessionId(), work.requestId(), current.getTurnNo(), session.getStatus(),
        nextTurn == null ? null : view(nextTurn), false);
    attempt.complete(encode(result));
    return result;
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
