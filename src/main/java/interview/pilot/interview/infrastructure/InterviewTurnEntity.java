package interview.pilot.interview.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.TurnStatus;
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
@Table(name = "interview_turn")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewTurnEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "turn_no", nullable = false)
  private int turnNo;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "request_id", unique = true, length = 36)
  private UUID requestId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private TurnStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Difficulty difficulty;

  @Column(name = "question_text", nullable = false, columnDefinition = "longtext")
  private String questionText;

  @Column(name = "target_competency", nullable = false, length = 100)
  private String targetCompetency;

  @Enumerated(EnumType.STRING)
  @Column(name = "rag_status", nullable = false, length = 32)
  private interview.pilot.interview.rag.RagStatus ragStatus =
      interview.pilot.interview.rag.RagStatus.NOT_CONFIGURED;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rag_context_snapshot", columnDefinition = "json")
  private String ragContextSnapshot;

  @Column(name = "answer_text", columnDefinition = "longtext")
  private String answerText;

  @Column(name = "feedback_text", columnDefinition = "longtext")
  private String feedbackText;

  @Column(precision = 5, scale = 2)
  private BigDecimal score;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evaluation_snapshot", columnDefinition = "json")
  private String evaluationSnapshot;

  @Column(name = "processing_error", length = 255)
  private String processingError;

  @Column(name = "asked_at", nullable = false)
  private Instant askedAt;

  @Column(name = "answered_at")
  private Instant answeredAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  public static InterviewTurnEntity firstAsked(
      Long sessionId,
      Difficulty difficulty,
      String questionText,
      String targetCompetency) {
    var turn = new InterviewTurnEntity();
    turn.sessionId = sessionId;
    turn.turnNo = 1;
    turn.requestId = null;
    turn.status = TurnStatus.ASKED;
    turn.difficulty = difficulty;
    turn.questionText = questionText;
    turn.targetCompetency = targetCompetency;
    turn.askedAt = Instant.now();
    return turn;
  }

  public static InterviewTurnEntity nextAsked(
      Long sessionId,
      int turnNo,
      Difficulty difficulty,
      String questionText,
      String targetCompetency) {
    var turn = firstAsked(sessionId, difficulty, questionText, targetCompetency);
    turn.turnNo = turnNo;
    return turn;
  }
}
