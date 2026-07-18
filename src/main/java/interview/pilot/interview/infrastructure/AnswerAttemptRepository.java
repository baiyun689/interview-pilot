package interview.pilot.interview.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnswerAttemptRepository extends JpaRepository<AnswerAttemptEntity, Long> {
  Optional<AnswerAttemptEntity> findByRequestId(UUID requestId);
}
