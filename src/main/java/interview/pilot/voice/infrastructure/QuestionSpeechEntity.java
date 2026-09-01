package interview.pilot.voice.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import interview.pilot.voice.domain.QuestionSpeechStatus;
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
@Table(name = "question_speech")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuestionSpeechEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "speech_id", nullable = false, unique = true, length = 36, updatable = false)
  private UUID speechId;

  @Column(name = "user_account_id", nullable = false, updatable = false)
  private Long userAccountId;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "turn_id", nullable = false, unique = true, updatable = false)
  private Long turnId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private QuestionSpeechStatus status;

  @Column(name = "text_sha256", nullable = false, length = 64)
  private String textSha256;

  @Column(name = "storage_key", length = 512)
  private String storageKey;

  @Column(name = "content_type", length = 80)
  private String contentType;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "duration_millis")
  private Long durationMillis;

  @Column(name = "provider_id", nullable = false, length = 64)
  private String providerId;

  @Column(name = "model_name", nullable = false, length = 128)
  private String modelName;

  @Column(name = "voice_name", nullable = false, length = 128)
  private String voiceName;

  @Column(name = "provider_request_id", length = 128)
  private String providerRequestId;

  @Column(name = "execution_epoch", nullable = false)
  private long executionEpoch;

  @Column(name = "safe_error", length = 255)
  private String safeError;

  @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version @Column(nullable = false)
  private long version;

  public static QuestionSpeechEntity pending(
      Long userAccountId, UUID speechId, Long sessionId, Long turnId,
      String textSha256, String providerId, String modelName, String voiceName) {
    var speech = new QuestionSpeechEntity();
    speech.userAccountId = Objects.requireNonNull(userAccountId);
    speech.speechId = Objects.requireNonNull(speechId);
    speech.sessionId = Objects.requireNonNull(sessionId);
    speech.turnId = Objects.requireNonNull(turnId);
    speech.textSha256 = Objects.requireNonNull(textSha256);
    speech.providerId = Objects.requireNonNull(providerId);
    speech.modelName = Objects.requireNonNull(modelName);
    speech.voiceName = Objects.requireNonNull(voiceName);
    speech.status = QuestionSpeechStatus.PENDING;
    return speech;
  }

  public void moveTo(QuestionSpeechStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalStateException("question speech cannot transition from " + status + " to " + target);
    }
    status = target;
  }
}
