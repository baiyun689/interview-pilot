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
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
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

@Entity
@Table(name = "interview_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewSessionEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_account_id", nullable = false, updatable = false)
  private Long userAccountId;

  @UuidGenerator @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "session_id", nullable = false, unique = true, length = 36, updatable = false)
  private UUID sessionId;

  @Column(name = "resume_id")
  private Long resumeId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private SessionStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 16)
  private Difficulty difficulty;

  @Enumerated(EnumType.STRING)
  @Column(name = "interview_size", nullable = false, updatable = false, length = 16)
  private InterviewSize interviewSize;

  @Enumerated(EnumType.STRING)
  @Column(name = "interview_mode", nullable = false, updatable = false, length = 16)
  private InterviewMode interviewMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "job_source_type", nullable = false, updatable = false, length = 16)
  private JobSourceType jobSourceType;

  @Column(name = "job_title", nullable = false, updatable = false, length = 200)
  private String jobTitle;

  @Column(name = "current_turn_no", nullable = false)
  private int currentTurnNo;

  @Column(name = "current_main_question_no", nullable = false)
  private int currentMainQuestionNo;

  @Column(name = "total_main_question_count", nullable = false, updatable = false)
  private int totalMainQuestionCount;

  @Column(name = "provider_id", nullable = false, updatable = false, length = 64)
  private String providerId;

  @Column(name = "model_name", nullable = false, updatable = false, length = 128)
  private String modelName;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "brief_snapshot", nullable = false, updatable = false, columnDefinition = "json")
  private String briefSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "knowledge_scope_snapshot", updatable = false, columnDefinition = "json")
  private String knowledgeScopeSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "voice_snapshot", updatable = false, columnDefinition = "json")
  private String voiceSnapshot;

  @Column(name = "safe_error", length = 255)
  private String safeError;

  @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Version @Column(nullable = false)
  private long version;

  public static InterviewSessionEntity preparing(
      Long userAccountId, Long resumeId, Difficulty difficulty, InterviewSize interviewSize,
      JobSourceType jobSourceType, String jobTitle, String providerId, String modelName,
      String briefSnapshot, String knowledgeScopeSnapshot) {
    return preparing(userAccountId, resumeId, difficulty, interviewSize, jobSourceType,
        jobTitle, providerId, modelName, briefSnapshot, knowledgeScopeSnapshot,
        InterviewMode.TEXT, null);
  }

  public static InterviewSessionEntity preparing(
      Long userAccountId, Long resumeId, Difficulty difficulty, InterviewSize interviewSize,
      JobSourceType jobSourceType, String jobTitle, String providerId, String modelName,
      String briefSnapshot, String knowledgeScopeSnapshot, InterviewMode interviewMode,
      String voiceSnapshot) {
    var session = new InterviewSessionEntity();
    session.userAccountId = Objects.requireNonNull(userAccountId);
    session.sessionId = UUID.randomUUID();
    session.resumeId = resumeId;
    session.status = SessionStatus.PREPARING;
    session.difficulty = Objects.requireNonNull(difficulty);
    session.interviewSize = Objects.requireNonNull(interviewSize);
    session.interviewMode = interviewMode == null ? InterviewMode.TEXT : interviewMode;
    session.jobSourceType = Objects.requireNonNull(jobSourceType);
    session.jobTitle = Objects.requireNonNull(jobTitle);
    session.totalMainQuestionCount = interviewSize.totalMainQuestionCount();
    session.providerId = Objects.requireNonNull(providerId);
    session.modelName = Objects.requireNonNull(modelName);
    session.briefSnapshot = Objects.requireNonNull(briefSnapshot);
    session.knowledgeScopeSnapshot = knowledgeScopeSnapshot;
    session.voiceSnapshot = voiceSnapshot;
    return session;
  }

  public void preparationReady() { transition(SessionStatus.READY, "preparation cannot complete"); safeError = null; }
  public void preparationFailed(String error) { transition(SessionStatus.PREPARATION_FAILED, "preparation cannot fail"); safeError = safe(error); }
  public void retryPreparation() { transition(SessionStatus.PREPARING, "preparation cannot retry"); safeError = null; }
  public void beginFixedInterview() {
    transition(SessionStatus.INTERVIEWING, "interview cannot start");
    currentTurnNo = 1;
    currentMainQuestionNo = 1;
  }

  public void advanceTo(int nextTurnNo, QuestionType questionType) {
    if (status != SessionStatus.INTERVIEWING || nextTurnNo != currentTurnNo + 1) {
      throw new IllegalStateException("interview cannot advance");
    }
    if (questionType != QuestionType.MAIN && questionType != QuestionType.FOLLOW_UP) {
      throw new IllegalArgumentException("next question type must be main or follow-up");
    }
    currentTurnNo = nextTurnNo;
    if (questionType == QuestionType.MAIN) currentMainQuestionNo++;
  }

  public void beginEvaluation() { transition(SessionStatus.EVALUATING, "evaluation cannot start"); }
  public void completeEvaluation() { transition(SessionStatus.COMPLETED, "evaluation cannot complete"); completedAt = Instant.now(); }
  public void evaluationFailed(String error) { transition(SessionStatus.EVALUATION_FAILED, "evaluation cannot fail"); safeError = safe(error); }
  public void retryEvaluation() { transition(SessionStatus.EVALUATING, "evaluation cannot retry"); safeError = null; }

  private void transition(SessionStatus target, String message) {
    if (!status.canTransitionTo(target)) throw new IllegalStateException(message);
    status = target;
  }

  private String safe(String error) {
    String value = error == null || error.isBlank() ? "UNKNOWN_ERROR" : error;
    return value.length() <= 255 ? value : value.substring(0, 255);
  }
}
