package interview.pilot.interview.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurnEntity, Long> {
  Optional<InterviewTurnEntity> findBySessionIdAndTurnNo(Long sessionId, int turnNo);

  /**
   * Claim-time locking read of the current turn (plan §8.4). Concurrent claims otherwise
   * deadlock on the turn row: the attempt INSERT's foreign-key check holds a shared lock
   * while the claim UPDATE needs an exclusive one. Locking the turn first serializes claims
   * so the loser re-reads the committed PROCESSING status and gets a stable
   * TURN_ALREADY_CLAIMED instead of a deadlock error (MySQL stays authoritative when the
   * Redis admission gate is unavailable).
   *
   * <p>The explicit {@code @Query} is required: the derived-query parser mis-parses the
   * {@code ForUpdate} suffix as a property path ({@code InterviewTurnEntity.turnNo.forUpdate}),
   * so this method must not be "simplified" back into a derived query.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select turn from InterviewTurnEntity turn "
      + "where turn.sessionId = :sessionId and turn.turnNo = :turnNo")
  Optional<InterviewTurnEntity> findBySessionIdAndTurnNoForUpdate(
      @Param("sessionId") Long sessionId, @Param("turnNo") int turnNo);

  Optional<InterviewTurnEntity> findByRequestId(UUID requestId);

  List<InterviewTurnEntity> findAllBySessionIdOrderByTurnNo(Long sessionId);
}
