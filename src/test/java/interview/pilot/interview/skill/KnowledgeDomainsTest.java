package interview.pilot.interview.skill;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class KnowledgeDomainsTest {

  @Test
  void registeredScopesAreAccepted() {
    assertThatCode(() -> KnowledgeDomains.requireRegistered(
        "java-backend", List.of("java", "concurrency", "spring")))
        .doesNotThrowAnyException();
  }

  @Test
  void emptyScopesAreAccepted() {
    assertThatCode(() -> KnowledgeDomains.requireRegistered("java-backend", List.of()))
        .doesNotThrowAnyException();
  }

  @Test
  void unknownScopeIsRejectedWithSkillContext() {
    assertThatThrownBy(() -> KnowledgeDomains.requireRegistered(
        "java-backend", List.of("made_up_domain")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("java-backend")
        .hasMessageContaining("made_up_domain");
  }
}
