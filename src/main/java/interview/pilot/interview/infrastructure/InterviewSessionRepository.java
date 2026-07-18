package interview.pilot.interview.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
  Optional<InterviewSessionEntity> findBySessionId(UUID sessionId);

  Optional<InterviewSessionEntity> findBySessionIdAndUserAccountId(
      UUID sessionId, Long userAccountId);

  List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

  List<InterviewSessionEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);
}
