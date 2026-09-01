package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import interview.pilot.interview.domain.InputMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.QuestionType;
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

@Entity
@Table(name = "interview_turn")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewTurnEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;
  @Column(name = "turn_no", nullable = false, updatable = false)
  private int turnNo;
  @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 32)
  private InterviewPhase phase;
  @Enumerated(EnumType.STRING) @Column(name = "question_type", nullable = false, updatable = false, length = 32)
  private QuestionType questionType;
  @Column(name = "source_card_id", nullable = false, updatable = false)
  private Long sourceCardId;
  @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_id", unique = true, length = 36)
  private UUID requestId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
  private TurnStatus status;
  @Column(name = "question_text", nullable = false, updatable = false, columnDefinition = "longtext")
  private String questionText;
  @Column(name = "answer_text", columnDefinition = "longtext")
  private String answerText;
  @Enumerated(EnumType.STRING) @Column(name = "input_mode", nullable = false, length = 16)
  private InputMode inputMode;
  @Column(name = "processing_error", length = 255)
  private String processingError;
  @Column(name = "asked_at", nullable = false, updatable = false)
  private Instant askedAt;
  @Column(name = "answered_at")
  private Instant answeredAt;
  @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
  @UpdateTimestamp @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
  @Version @Column(nullable = false)
  private long version;

  public static InterviewTurnEntity asked(
      Long sessionId, int turnNo, InterviewPhase phase, QuestionType questionType,
      Long sourceCardId, String questionText) {
    var turn = new InterviewTurnEntity();
    turn.sessionId = Objects.requireNonNull(sessionId);
    turn.turnNo = turnNo;
    turn.phase = Objects.requireNonNull(phase);
    turn.questionType = Objects.requireNonNull(questionType);
    turn.sourceCardId = Objects.requireNonNull(sourceCardId);
    turn.status = TurnStatus.ASKED;
    turn.questionText = Objects.requireNonNull(questionText);
    // V21 makes input_mode NOT NULL; an unanswered turn has no real input yet, so it starts as TEXT.
    turn.inputMode = InputMode.TEXT;
    turn.askedAt = Instant.now();
    return turn;
  }

  public void beginAnswer(UUID requestId, String answerText, InputMode inputMode) {
    if (status != TurnStatus.ASKED && status != TurnStatus.FAILED) throw new IllegalStateException("turn cannot be claimed");
    if (requestId == null || answerText == null || answerText.isBlank() || inputMode == null)
      throw new IllegalArgumentException("answer is required");
    this.requestId = requestId;
    this.answerText = answerText.trim();
    this.inputMode = inputMode;
    this.status = TurnStatus.PROCESSING;
    this.processingError = null;
  }

  public void completeAnswer() {
    if (status != TurnStatus.PROCESSING) throw new IllegalStateException("turn cannot complete");
    status = TurnStatus.COMPLETED;
    answeredAt = Instant.now();
    processingError = null;
  }

  public void failAnswer(String error) {
    if (status != TurnStatus.PROCESSING) throw new IllegalStateException("turn cannot fail");
    status = TurnStatus.FAILED;
    processingError = error;
  }
}
