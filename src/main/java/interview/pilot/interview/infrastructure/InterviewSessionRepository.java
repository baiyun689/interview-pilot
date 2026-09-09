package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import java.util.List;
import interview.pilot.interview.domain.SessionStatus;
import jakarta.persistence.LockModeType;

public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {
  Optional<InterviewSessionEntity> findBySessionId(UUID sessionId);

  @Query(value = "select count(*) from hiring_interview_invitation i "
      + "join hiring_batch_member m on m.id=i.batch_member_id join hiring_batch b on b.id=m.batch_id "
      + "join hiring_organization o on o.id=b.organization_id join hiring_application a on a.id=i.application_id "
      + "where i.id=:id and i.status='STARTED' and o.active=true "
      + "and a.submission_no=m.submission_no and a.status not in ('WITHDRAWN','FINISHED')", nativeQuery = true)
  long countActiveHiringInvitation(@Param("id") Long invitationId);

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

  /**
   * Retention sweep (Task 11): bounded batch of COMPLETED sessions whose retention window has
   * passed. The interview_session table is small (one row per interview), so the scan needs no
   * new index — see the cleanup service's Javadoc for the index decisions.
   */
  Page<InterviewSessionEntity> findAllByStatusAndCompletedAtBefore(
      SessionStatus status, Instant before, Pageable pageable);

  /**
   * PESSIMISTIC_WRITE re-read for the cleanup sweeper's mark and completion-fact transactions
   * (Task 11): the COMPLETED + retention predicate is re-verified under the session lock.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from InterviewSessionEntity s where s.id = :id")
  Optional<InterviewSessionEntity> findByIdForUpdate(@Param("id") Long id);

  @Modifying
  @Query("update InterviewSessionEntity s set s.resumeId = null where s.resumeId = :resumeId")
  int clearResumeReference(@Param("resumeId") Long resumeId);
}
