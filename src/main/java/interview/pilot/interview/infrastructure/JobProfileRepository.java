package interview.pilot.interview.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobProfileRepository extends JpaRepository<JobProfileEntity, Long> {
  Optional<JobProfileEntity> findByJobId(UUID jobId);
}
