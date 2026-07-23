package interview.pilot.async.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "async_task")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AsyncTaskEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Setter(AccessLevel.NONE)
  @Column(name = "user_account_id", nullable = false)
  private Long userAccountId;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "task_id", nullable = false, unique = true, length = 36)
  private UUID taskId;

  @Enumerated(EnumType.STRING)
  @Column(name = "task_type", nullable = false, length = 64)
  private AsyncTaskType taskType;

  @Column(name = "biz_key", nullable = false)
  private String bizKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AsyncTaskStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload_snapshot", nullable = false, columnDefinition = "json")
  private String payloadSnapshot;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "execution_epoch", nullable = false)
  private int executionEpoch;

  @Column(name = "publish_attempts", nullable = false)
  private int publishAttempts;

  @Column(name = "last_published_at")
  private Instant lastPublishedAt;

  @Column(name = "last_error")
  private String lastError;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static AsyncTaskEntity pending(
      Long userAccountId,
      AsyncTaskType taskType,
      String bizKey,
      String payloadSnapshot) {
    var task = new AsyncTaskEntity();
    task.userAccountId = Objects.requireNonNull(userAccountId, "userAccountId");
    task.taskType = taskType;
    task.bizKey = bizKey;
    task.status = AsyncTaskStatus.PENDING;
    task.payloadSnapshot = payloadSnapshot;
    task.attemptCount = 0;
    task.executionEpoch = 0;
    task.publishAttempts = 0;
    return task;
  }

}
