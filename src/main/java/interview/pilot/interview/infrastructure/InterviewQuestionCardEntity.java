package interview.pilot.interview.infrastructure;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.rag.RagStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "interview_question_card")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterviewQuestionCardEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 32)
  private InterviewPhase phase;

  @Column(name = "phase_sequence", nullable = false, updatable = false)
  private int phaseSequence;

  @Column(nullable = false, updatable = false, length = 60)
  private String topic;

  @Column(name = "question_text", nullable = false, updatable = false, columnDefinition = "longtext")
  private String questionText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "focus_points", nullable = false, updatable = false, columnDefinition = "json")
  private String focusPoints;

  @Column(name = "knowledge_point", updatable = false, length = 128)
  private String knowledgePoint;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "retrieval_keywords", updatable = false, columnDefinition = "json")
  private String retrievalKeywords;

  @Enumerated(EnumType.STRING)
  @Column(name = "grounding_mode", nullable = false, updatable = false, length = 32)
  private GroundingMode groundingMode;

  @Enumerated(EnumType.STRING)
  @Column(name = "rag_status", nullable = false, updatable = false, length = 32)
  private RagStatus ragStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rag_context_snapshot", nullable = false, updatable = false, columnDefinition = "json")
  private String ragContextSnapshot;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_ids", nullable = false, updatable = false, columnDefinition = "json")
  private String sourceIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rubric", updatable = false, columnDefinition = "json")
  private String rubric;

  @Column(name = "follow_up_quota", nullable = false, updatable = false)
  private int followUpQuota;

  @Column(name = "fallback_follow_up", updatable = false, length = 160)
  private String fallbackFollowUp;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Legacy factory used by the fixed self-introduction card (no question-scoped grounding). */
  public static InterviewQuestionCardEntity create(
      Long sessionId, InterviewPhase phase, int phaseSequence, String topic,
      String questionText, String focusPoints, GroundingMode groundingMode,
      RagStatus ragStatus, String ragContextSnapshot, String sourceIds,
      int followUpQuota, String fallbackFollowUp) {
    return create(
        sessionId, phase, phaseSequence, topic, questionText, focusPoints,
        null, null, groundingMode, ragStatus, ragContextSnapshot, sourceIds, null,
        followUpQuota, fallbackFollowUp);
  }

  public static InterviewQuestionCardEntity create(
      Long sessionId, InterviewPhase phase, int phaseSequence, String topic,
      String questionText, String focusPoints, String knowledgePoint, String retrievalKeywords,
      GroundingMode groundingMode, RagStatus ragStatus, String ragContextSnapshot,
      String sourceIds, String rubric, int followUpQuota, String fallbackFollowUp) {
    if (phase == InterviewPhase.SELF_INTRODUCTION && followUpQuota != 0) {
      throw new IllegalArgumentException("self introduction follow-up quota must be zero");
    }
    if (!phase.allowsFollowUp() && followUpQuota != 0) {
      throw new IllegalArgumentException("phase does not allow follow-ups");
    }
    if (phase.allowsFollowUp() && (followUpQuota < 1 || followUpQuota > 2)) {
      throw new IllegalArgumentException("follow-up quota must be 1 or 2");
    }
    var card = new InterviewQuestionCardEntity();
    card.sessionId = Objects.requireNonNull(sessionId);
    card.phase = Objects.requireNonNull(phase);
    card.phaseSequence = phaseSequence;
    card.topic = Objects.requireNonNull(topic);
    card.questionText = Objects.requireNonNull(questionText);
    card.focusPoints = Objects.requireNonNull(focusPoints);
    card.knowledgePoint = knowledgePoint;
    card.retrievalKeywords = retrievalKeywords;
    card.groundingMode = Objects.requireNonNull(groundingMode);
    card.ragStatus = Objects.requireNonNull(ragStatus);
    card.ragContextSnapshot = Objects.requireNonNull(ragContextSnapshot);
    card.sourceIds = Objects.requireNonNull(sourceIds);
    card.rubric = rubric;
    card.followUpQuota = followUpQuota;
    card.fallbackFollowUp = fallbackFollowUp;
    return card;
  }
}
