package interview.pilot.voice.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import interview.pilot.voice.domain.VoiceRecordingStatus;

public interface VoiceRecordingRepository extends JpaRepository<VoiceRecordingEntity, Long> {
  Optional<VoiceRecordingEntity> findByRecordingId(UUID recordingId);

  Optional<VoiceRecordingEntity> findByUploadRequestId(UUID uploadRequestId);

  List<VoiceRecordingEntity> findAllBySessionIdAndTurnIdOrderByCreatedAt(
      Long sessionId, Long turnId);

  List<VoiceRecordingEntity> findAllByStatusInAndExpiresAtBefore(
      Collection<VoiceRecordingStatus> statuses, Instant before);
}
