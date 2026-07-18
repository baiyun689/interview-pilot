package interview.pilot.resume.infrastructure;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {
  Optional<ResumeEntity> findByUserAccountIdAndContentHash(Long userAccountId, String contentHash);

  List<ResumeEntity> findAllByOrderByCreatedAtDesc();
}
