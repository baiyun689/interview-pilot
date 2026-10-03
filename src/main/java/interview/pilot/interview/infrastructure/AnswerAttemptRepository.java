package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerAttemptRepository extends JpaRepository<AnswerAttemptEntity, Long> {
  Optional<AnswerAttemptEntity> findByRequestId(UUID requestId);

  // Project identifiers only: each recovery transaction must read fresh state under its locks.
  @Query("select a.id as id, a.sessionId as sessionId "
      + "from AnswerAttemptEntity a where a.status = interview.pilot.interview.domain.AnswerAttemptStatus.PROCESSING "
      + "and a.createdAt < :cutoff and a.id > :afterId order by a.id")
  List<RecoveryCandidate> findExpired(
      @Param("cutoff") Instant cutoff, @Param("afterId") long afterId, Pageable pageable);

  interface RecoveryCandidate {
    Long getId();
    Long getSessionId();
  }
}
