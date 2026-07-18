package interview.pilot.interview.infrastructure;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewReportRepository extends JpaRepository<InterviewReportEntity, Long> {
  Optional<InterviewReportEntity> findBySessionId(Long sessionId);
}
