package interview.pilot.interview.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurnEntity, Long> {
  Optional<InterviewTurnEntity> findBySessionIdAndTurnNo(Long sessionId, int turnNo);

  Optional<InterviewTurnEntity> findByRequestId(UUID requestId);

  List<InterviewTurnEntity> findAllBySessionIdOrderByTurnNo(Long sessionId);
}
