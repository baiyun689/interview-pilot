package interview.pilot.ai.provider;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSettingRepository extends JpaRepository<AiSettingEntity, Long> {
  Optional<AiSettingEntity> findBySettingKey(String settingKey);
}
