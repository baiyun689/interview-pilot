package interview.pilot.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class KnowledgeRepositoryAdapterConditionTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
      .withBean(KnowledgeBaseJpaRepository.class, () -> mock(KnowledgeBaseJpaRepository.class))
      .withBean(KnowledgeDocumentJpaRepository.class, () -> mock(KnowledgeDocumentJpaRepository.class))
      .withUserConfiguration(AdapterConfiguration.class);

  @Test
  void adaptersAreRegisteredWhenJpaInfrastructureIsAvailable() {
    contextRunner.run(context -> {
      assertThat(context).hasSingleBean(KnowledgeBaseRepository.class);
      assertThat(context).hasSingleBean(KnowledgeDocumentRepository.class);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @Import({KnowledgeBaseRepositoryAdapter.class, KnowledgeDocumentRepositoryAdapter.class})
  static class AdapterConfiguration {}
}
