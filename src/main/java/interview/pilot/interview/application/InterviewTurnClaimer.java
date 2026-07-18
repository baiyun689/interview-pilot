package interview.pilot.interview.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.AnswerAttemptStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.common.observability.AiMetrics;

@Component
public class InterviewTurnClaimer {
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final AnswerAttemptRepository attempts;
  private final TransactionTemplate requiresNew;
  private final AiMetrics metrics;

  public InterviewTurnClaimer(
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      AnswerAttemptRepository attempts,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.sessions = sessions;
    this.turns = turns;
    this.attempts = attempts;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.metrics = metrics;
  }

  public InterviewTurnClaim claim(CurrentUser user, UUID sessionId, UUID requestId, String answer) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(requestId, "requestId must not be null");
    Long ownerId = requireOwner(user);
    String normalizedAnswer = normalizeAnswer(answer);
    try {
      return requiresNew.execute(
          status -> createOrReplay(ownerId, sessionId, requestId, normalizedAnswer));
    } catch (ObjectOptimisticLockingFailureException
        | DataIntegrityViolationException
        | CannotAcquireLockException race) {
      if (race instanceof ObjectOptimisticLockingFailureException) {
        metrics.optimisticLockConflict();
      }
      // The failed transaction is fully rolled back before the winning row is queried.
      InterviewTurnClaim recovered = requiresNew.execute(
          status -> recoverRace(ownerId, sessionId, requestId, normalizedAnswer));
      if (recovered != null) {
        return recovered;
      }
      if (race instanceof DataIntegrityViolationException) {
        throw race;
      }
      throw conflict("ANSWER_RACE", "The answer is being processed; retry shortly");
    }
  }

  @Deprecated(forRemoval = true)
  public InterviewTurnClaim claim(UUID sessionId, UUID requestId, String answer) {
    return claim(legacyUser(), sessionId, requestId, answer);
  }

  private InterviewTurnClaim createOrReplay(
      Long ownerId, UUID publicSessionId, UUID requestId, String answer) {
    var session = session(ownerId, publicSessionId);
    var existing = attempts.findByRequestId(requestId);
    if (existing.isPresent()) {
      return replay(session.getId(), existing.get(), answer);
    }
    if (session.getStatus() != SessionStatus.INTERVIEWING) {
      throw conflict("INTERVIEW_NOT_ACCEPTING_ANSWERS", "Interview is not accepting answers");
    }
    InterviewTurnEntity turn = turns.findBySessionIdAndTurnNo(
        session.getId(), session.getCurrentTurnNo()).orElseThrow(
            () -> new IllegalStateException("Current interview turn is missing"));
    if (turn.getStatus() == TurnStatus.PROCESSING || turn.getStatus() == TurnStatus.COMPLETED) {
      throw conflict("TURN_ALREADY_CLAIMED", "The current turn was claimed by another request");
    }
    if (turn.getStatus() != TurnStatus.ASKED && turn.getStatus() != TurnStatus.FAILED) {
      throw conflict("TURN_NOT_ANSWERABLE", "The current turn cannot be answered");
    }
    AnswerAttemptEntity attempt = attempts.saveAndFlush(AnswerAttemptEntity.processing(
        requestId, session.getId(), turn.getId(), AnswerFingerprint.sha256(answer)));
    turn.setRequestId(requestId);
    turn.setAnswerText(answer);
    turn.setStatus(TurnStatus.PROCESSING);
    turn.setProcessingError(null);
    turn.setEvaluationSnapshot(null);
    turn = turns.saveAndFlush(turn);
    return claimOf(InterviewTurnClaim.State.OWNER, turn, attempt);
  }

  private InterviewTurnClaim recoverRace(
      Long ownerId, UUID publicSessionId, UUID requestId, String answer) {
    var session = sessions.findBySessionIdAndUserAccountId(publicSessionId, ownerId).orElse(null);
    if (session == null) {
      return null;
    }
    var winner = attempts.findByRequestId(requestId).orElse(null);
    if (winner != null) {
      return replay(session.getId(), winner, answer);
    }
    InterviewTurnEntity current = turns.findBySessionIdAndTurnNo(
        session.getId(), session.getCurrentTurnNo()).orElse(null);
    if (current != null && current.getRequestId() != null) {
      throw conflict("TURN_ALREADY_CLAIMED", "The current turn was claimed by another request");
    }
    return null;
  }

  private InterviewTurnClaim replay(
      Long expectedSessionId, AnswerAttemptEntity attempt, String answer) {
    if (!expectedSessionId.equals(attempt.getSessionId())
        || !AnswerFingerprint.sha256(answer).equals(attempt.getAnswerHash())) {
      throw conflict("IDEMPOTENCY_KEY_REUSED", "The requestId was already used for another answer");
    }
    InterviewTurnEntity turn = turns.findById(attempt.getTurnId())
        .orElseThrow(() -> new IllegalStateException("Answer attempt turn is missing"));
    if (!attempt.getSessionId().equals(turn.getSessionId())) {
      throw new IllegalStateException("Answer attempt identity is invalid");
    }
    return claimOf(switch (attempt.getStatus()) {
      case PROCESSING -> InterviewTurnClaim.State.PROCESSING;
      case COMPLETED -> InterviewTurnClaim.State.COMPLETED;
      case FAILED -> InterviewTurnClaim.State.FAILED;
    }, turn, attempt);
  }

  private InterviewTurnClaim claimOf(
      InterviewTurnClaim.State state, InterviewTurnEntity turn, AnswerAttemptEntity attempt) {
    return new InterviewTurnClaim(
        state, turn.getSessionId(), turn.getId(), attempt.getId(), attempt.getRequestId(),
        attempt.getAnswerHash(),
        turn.getTurnNo(), turn.getVersion(), attempt.getVersion(), attempt.getStatus(),
        attempt.getResultSnapshot(), attempt.getSafeError());
  }


  private String normalizeAnswer(String answer) {
    String normalized = answer == null ? "" : answer.trim();
    if (normalized.isEmpty() || normalized.length() > 20_000) {
      throw new BusinessException("INVALID_ANSWER", "The answer is invalid", HttpStatus.BAD_REQUEST);
    }
    return normalized;
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private InterviewSessionEntity session(Long ownerId, UUID sessionId) {
    return sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(() -> new BusinessException(
            "INTERVIEW_NOT_FOUND", "Interview session not found", HttpStatus.NOT_FOUND));
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private static CurrentUser legacyUser() {
    return new CurrentUser(1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  }
}
