package interview.pilot.async.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
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
   * Atomically records a pending task as published before its RabbitMQ message is sent.
   * A dispatcher that loses this compare-and-set must not publish a duplicate message.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("""
      update AsyncTaskEntity task
         set task.publishAttempts = task.publishAttempts + 1,
             task.lastPublishedAt = :publishedAt,
             task.lastError = null,
             task.version = task.version + 1
       where task.id = :databaseId
         and task.status = :expectedStatus
         and task.executionEpoch = :executionEpoch
         and (task.lastPublishedAt is null or task.lastPublishedAt < :cutoff)
      """)
  int claimForPublishing(
      @Param("databaseId") Long databaseId,
      @Param("executionEpoch") int executionEpoch,
      @Param("publishedAt") Instant publishedAt,
      @Param("cutoff") Instant cutoff,
      @Param("expectedStatus") AsyncTaskStatus expectedStatus);

  /** Releases a claim after a synchronous broker publication failure. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("""
      update AsyncTaskEntity task
         set task.publishAttempts = task.publishAttempts - 1,
             task.lastPublishedAt = null,
             task.lastError = :safeError,
             task.version = task.version + 1
       where task.id = :databaseId
         and task.status = :expectedStatus
         and task.executionEpoch = :executionEpoch
      """)
  int releasePublishingClaim(
      @Param("databaseId") Long databaseId,
      @Param("executionEpoch") int executionEpoch,
      @Param("safeError") String safeError,
      @Param("expectedStatus") AsyncTaskStatus expectedStatus);

  /**
   * Stuck-task recovery (Task 11): bounded batch of voice tasks that were PUBLISHED but have
   * seen no row activity since {@code before}. {@code updated_at} moves on every listener
   * touch (claim, retryable failure evidence, terminal result), so a healthy pipeline is never
   * older than the delayed-retry ladder — an old row means the message is gone and the
   * recording/speech is stuck (pending dispatcher only rescans PENDING).
   */
  Page<AsyncTaskEntity> findByTaskTypeAndStatusAndUpdatedAtBefore(
      AsyncTaskType type, AsyncTaskStatus status, Instant before, Pageable pageable);
}
