package interview.pilot.async.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;

public interface AsyncTaskRepository extends JpaRepository<AsyncTaskEntity, Long> {
  Optional<AsyncTaskEntity> findByTaskId(UUID taskId);

  Optional<AsyncTaskEntity> findByTaskIdAndUserAccountId(UUID taskId, Long userAccountId);

  Optional<AsyncTaskEntity> findByIdAndUserAccountId(Long id, Long userAccountId);

  Optional<AsyncTaskEntity> findByTaskTypeAndBizKey(AsyncTaskType type, String bizKey);

  Optional<AsyncTaskEntity> findByTaskTypeAndBizKeyAndUserAccountId(
      AsyncTaskType type, String bizKey, Long userAccountId);

  void deleteByTaskTypeAndBizKeyAndUserAccountId(
      AsyncTaskType type, String bizKey, Long userAccountId);

  @Query("""
      select task from AsyncTaskEntity task
      where task.status = :status
        and (task.lastPublishedAt is null or task.lastPublishedAt < :cutoff)
      order by task.createdAt
      """)
  List<AsyncTaskEntity> findDispatchable(
      @Param("status") AsyncTaskStatus status,
      @Param("cutoff") Instant cutoff,
      Pageable pageable);

  /**
   * Stuck-task recovery (Task 11): bounded batch of voice tasks that were PUBLISHED but have
   * seen no row activity since {@code before}. {@code updated_at} moves on every listener
   * touch (claim, retryable failure evidence, terminal result), so a healthy pipeline is never
   * older than the delayed-retry ladder — an old row means the message is gone and the
   * recording/speech is stuck (pending dispatcher only rescans PENDING).
   */
  List<AsyncTaskEntity> findByTaskTypeAndStatusAndUpdatedAtBefore(
      AsyncTaskType type, AsyncTaskStatus status, Instant before, Pageable pageable);
}
