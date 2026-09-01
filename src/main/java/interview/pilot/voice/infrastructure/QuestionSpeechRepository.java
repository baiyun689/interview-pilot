package interview.pilot.voice.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionSpeechRepository extends JpaRepository<QuestionSpeechEntity, Long> {
  Optional<QuestionSpeechEntity> findBySpeechId(UUID speechId);

  Optional<QuestionSpeechEntity> findByTurnId(Long turnId);
}
