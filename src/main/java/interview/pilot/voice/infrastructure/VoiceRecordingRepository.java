package interview.pilot.voice.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import interview.pilot.voice.domain.VoiceRecordingStatus;
import jakarta.persistence.LockModeType;

public interface VoiceRecordingRepository extends JpaRepository<VoiceRecordingEntity, Long> {
  Optional<VoiceRecordingEntity> findByRecordingId(UUID recordingId);

  Optional<VoiceRecordingEntity> findByUploadRequestId(UUID uploadRequestId);

  List<VoiceRecordingEntity> findAllBySessionIdAndTurnIdOrderByCreatedAt(
      Long sessionId, Long turnId);

  List<VoiceRecordingEntity> findAllByStatusInAndExpiresAtBefore(
      Collection<VoiceRecordingStatus> statuses, Instant before);

  /** Bounded cleanup batch (Task 11): RECEIVING residue older than the 10-minute TTL. */
  Page<VoiceRecordingEntity> findAllByStatusInAndExpiresAtBefore(
      Collection<VoiceRecordingStatus> statuses, Instant before, Pageable pageable);

  /** Bounded cleanup batch (Task 11): discarded recordings, regardless of expiry. */
  Page<VoiceRecordingEntity> findAllByStatusIn(
      Collection<VoiceRecordingStatus> statuses, Pageable pageable);

  /** Retention sweep (Task 11): every recording of an expired COMPLETED session. */
  List<VoiceRecordingEntity> findAllBySessionId(Long sessionId);

  /**
   * PESSIMISTIC_WRITE re-read for the cleanup sweeper's mark and completion-fact transactions
   * (Task 11): the deletion predicate is re-verified under the row lock so a concurrent upload
   * upgrade or a parallel sweep can never race a row deletion.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from VoiceRecordingEntity r where r.id = :id")
  Optional<VoiceRecordingEntity> findByIdForUpdate(@Param("id") Long id);
}
