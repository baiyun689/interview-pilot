package interview.pilot.interview.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import java.util.List;
import jakarta.persistence.LockModeType;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
  Optional<InterviewSessionEntity> findBySessionId(UUID sessionId);

  Optional<InterviewSessionEntity> findBySessionIdAndUserAccountId(
      UUID sessionId, Long userAccountId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from InterviewSessionEntity s where s.sessionId = :sessionId "
      + "and s.userAccountId = :userAccountId")
  Optional<InterviewSessionEntity> findForStart(
      @Param("sessionId") UUID sessionId, @Param("userAccountId") Long userAccountId);

  Optional<InterviewSessionEntity> findByIdAndUserAccountId(Long id, Long userAccountId);

  List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

  List<InterviewSessionEntity> findAllByUserAccountIdOrderByCreatedAtDesc(Long userAccountId);

  @Modifying
  @Query("update InterviewSessionEntity s set s.resumeId = null where s.resumeId = :resumeId")
  int clearResumeReference(@Param("resumeId") Long resumeId);
}
