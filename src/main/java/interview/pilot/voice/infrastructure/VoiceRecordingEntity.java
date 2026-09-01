package interview.pilot.voice.infrastructure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import interview.pilot.voice.domain.VoiceRecordingStatus;
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
@Table(name = "voice_recording")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoiceRecordingEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "recording_id", nullable = false, unique = true, length = 36, updatable = false)
  private UUID recordingId;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "upload_request_id", nullable = false, unique = true, length = 36, updatable = false)
  private UUID uploadRequestId;

  @Column(name = "user_account_id", nullable = false, updatable = false)
  private Long userAccountId;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "turn_id", nullable = false, updatable = false)
  private Long turnId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private VoiceRecordingStatus status;

  @Column(name = "storage_key", length = 512)
  private String storageKey;

  @Column(name = "content_type", length = 80)
  private String contentType;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "duration_millis")
  private Long durationMillis;

  @Column(name = "sha256", length = 64)
  private String sha256;

  @Column(name = "provider_id", length = 64)
  private String providerId;

  @Column(name = "model_name", length = 128)
  private String modelName;

  @Column(name = "provider_request_id", length = 128)
  private String providerRequestId;

  @Column(name = "raw_transcript", columnDefinition = "longtext")
  private String rawTranscript;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "attached_answer_request_id", length = 36)
  private UUID attachedAnswerRequestId;

  @Column(name = "execution_epoch", nullable = false)
  private long executionEpoch;

  @Column(name = "safe_error", length = 255)
  private String safeError;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version @Column(nullable = false)
  private long version;

  public static VoiceRecordingEntity receiving(
      Long userAccountId, UUID recordingId, UUID uploadRequestId,
      Long sessionId, Long turnId, Instant expiresAt) {
    var recording = new VoiceRecordingEntity();
    recording.userAccountId = Objects.requireNonNull(userAccountId);
    recording.recordingId = Objects.requireNonNull(recordingId);
    recording.uploadRequestId = Objects.requireNonNull(uploadRequestId);
    recording.sessionId = Objects.requireNonNull(sessionId);
    recording.turnId = Objects.requireNonNull(turnId);
    recording.expiresAt = Objects.requireNonNull(expiresAt);
    recording.status = VoiceRecordingStatus.RECEIVING;
    return recording;
  }

  public void moveTo(VoiceRecordingStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new IllegalStateException("recording cannot transition from " + status + " to " + target);
    }
    status = target;
  }

  /** RECEIVING → UPLOADED: the media is stored at the immutable key and its metadata is fixed. */
  public void acceptUpload(
      String storageKey, String contentType, long sizeBytes, long durationMillis, String sha256) {
    moveTo(VoiceRecordingStatus.UPLOADED);
    this.storageKey = storageKey;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.durationMillis = durationMillis;
    this.sha256 = sha256;
  }

  /**
   * RECEIVING → FAILED: the upload was rejected after phase 1. {@code contentSha256} is the
   * digest of the fully streamed content when it was computable (probe/duration rejections),
   * which lets an identical-bytes replay of the requestId return this failure instead of
   * REQUEST_ID_CONFLICT; a size rejection interrupts streaming so the digest stays null.
   */
  public void failUpload(String error, String contentSha256) {
    moveTo(VoiceRecordingStatus.FAILED);
    this.safeError = error;
    if (contentSha256 != null) {
      this.sha256 = contentSha256;
    }
  }

  /** FAILED → TRANSCRIBING with a fenced execution epoch (V9 precedent): stale messages lose. */
  public void beginTranscription() {
    moveTo(VoiceRecordingStatus.TRANSCRIBING);
    executionEpoch++;
  }

  /** RECEIVING/READY/FAILED → DISCARDED: the recording can never be bound to an answer. */
  public void discard() {
    moveTo(VoiceRecordingStatus.DISCARDED);
  }
}
