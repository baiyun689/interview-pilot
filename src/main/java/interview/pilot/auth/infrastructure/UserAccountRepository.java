package interview.pilot.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, Long> {
  Optional<UserAccountEntity> findByEmail(String email);

  Optional<UserAccountEntity> findByUserId(UUID userId);

  boolean existsByEmail(String email);
}
