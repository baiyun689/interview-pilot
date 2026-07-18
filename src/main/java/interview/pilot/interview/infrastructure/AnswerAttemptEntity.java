package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import interview.pilot.interview.domain.AnswerAttemptStatus;
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
@Table(name = "answer_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnswerAttemptEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "request_id", nullable = false, unique = true, length = 36, updatable = false)
  private UUID requestId;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "turn_id", nullable = false, updatable = false)
  private Long turnId;

  @Column(name = "answer_hash", nullable = false, length = 64, updatable = false)
  private String answerHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AnswerAttemptStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_snapshot", columnDefinition = "json")
  private String resultSnapshot;

  @Column(name = "safe_error", length = 255)
  private String safeError;

  @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version @Column(nullable = false)
  private long version;

  public static AnswerAttemptEntity processing(
      UUID requestId, Long sessionId, Long turnId, String answerHash) {
    var attempt = new AnswerAttemptEntity();
    attempt.requestId = requestId;
    attempt.sessionId = sessionId;
    attempt.turnId = turnId;
    attempt.answerHash = answerHash;
    attempt.status = AnswerAttemptStatus.PROCESSING;
    return attempt;
  }

  public void complete(String snapshot) {
    if (status != AnswerAttemptStatus.PROCESSING || snapshot == null || snapshot.isBlank()) {
      throw new IllegalStateException("Answer attempt cannot complete");
    }
    status = AnswerAttemptStatus.COMPLETED;
    resultSnapshot = snapshot;
    safeError = null;
  }

  public void fail(String error) {
    if (status != AnswerAttemptStatus.PROCESSING || error == null || error.isBlank()) {
      throw new IllegalStateException("Answer attempt cannot fail");
    }
    status = AnswerAttemptStatus.FAILED;
    safeError = error;
  }
}
