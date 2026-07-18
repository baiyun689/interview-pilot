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
}
