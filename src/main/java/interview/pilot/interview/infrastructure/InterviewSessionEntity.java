package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.SessionStatus;
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
@Table(name = "interview_session")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSessionEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_account_id", nullable = false, updatable = false)
  private Long userAccountId;

  @UuidGenerator
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "session_id", nullable = false, unique = true, length = 36)
  private UUID sessionId;

  @Column(name = "resume_id")
  private Long resumeId;

  @Column(name = "job_profile_id", nullable = false)
  private Long jobProfileId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private SessionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Difficulty difficulty;

  @Column(name = "current_turn_no", nullable = false)
  private int currentTurnNo;

  @Column(name = "total_turn_budget", nullable = false)
  private int totalTurnBudget;

  @Setter(AccessLevel.NONE)
  @Column(name = "provider_id", nullable = false, updatable = false, length = 64)
  private String providerId;

  @Setter(AccessLevel.NONE)
  @Column(name = "model_name", nullable = false, updatable = false, length = 128)
  private String modelName;

  @Setter(AccessLevel.NONE)
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "plan_snapshot", nullable = false, updatable = false, columnDefinition = "json")
  private String planSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "context_snapshot", columnDefinition = "json")
  private String contextSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "knowledge_scope_snapshot", columnDefinition = "json")
  private String knowledgeScopeSnapshot;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static InterviewSessionEntity create(
      Long userAccountId,
      Long resumeId,
      Long jobProfileId,
      Difficulty difficulty,
      int totalTurnBudget,
      String providerId,
      String modelName,
      String planSnapshot) {
    var session = new InterviewSessionEntity();
    session.userAccountId = Objects.requireNonNull(userAccountId, "userAccountId");
    session.sessionId = UUID.randomUUID();
    session.resumeId = resumeId;
    session.jobProfileId = jobProfileId;
    session.status = SessionStatus.CREATED;
    session.difficulty = difficulty;
    session.currentTurnNo = 0;
    session.totalTurnBudget = totalTurnBudget;
    session.providerId = providerId;
    session.modelName = modelName;
    session.planSnapshot = planSnapshot;
    return session;
  }

  @Deprecated(forRemoval = true)
  public static InterviewSessionEntity create(
      Long resumeId, Long jobProfileId, Difficulty difficulty, int totalTurnBudget,
      String providerId, String modelName, String planSnapshot) {
    return create(1L, resumeId, jobProfileId, difficulty, totalTurnBudget,
        providerId, modelName, planSnapshot);
  }

  public void start() {
    if (!status.canTransitionTo(SessionStatus.INTERVIEWING)) {
      throw new IllegalStateException("Interview session cannot be started");
    }
    status = SessionStatus.INTERVIEWING;
    currentTurnNo = 1;
  }

  public void advanceTo(int nextTurnNo, Difficulty nextDifficulty) {
    if (status != SessionStatus.INTERVIEWING || nextTurnNo != currentTurnNo + 1) {
      throw new IllegalStateException("Interview session cannot advance");
    }
    currentTurnNo = nextTurnNo;
    difficulty = nextDifficulty;
  }

  public void beginEvaluation() {
    if (!status.canTransitionTo(SessionStatus.EVALUATING)) {
      throw new IllegalStateException("Interview session cannot begin evaluation");
    }
    status = SessionStatus.EVALUATING;
  }

  public void completeEvaluation() {
    if (!status.canTransitionTo(SessionStatus.COMPLETED)) {
      throw new IllegalStateException("Interview session cannot complete evaluation");
    }
    status = SessionStatus.COMPLETED;
    completedAt = Instant.now();
  }
}
