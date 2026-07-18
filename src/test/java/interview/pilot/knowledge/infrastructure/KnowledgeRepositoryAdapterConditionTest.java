package interview.pilot.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class KnowledgeRepositoryAdapterConditionTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          HibernateJpaAutoConfiguration.class,
          DataJpaRepositoriesAutoConfiguration.class,
          KnowledgeRepositoryAdapterAutoConfiguration.class));

  @Test
  void adaptersAreNotRegisteredWithoutJpaDelegates() {
    contextRunner.run(context -> {
      assertThat(context).doesNotHaveBean(KnowledgeBaseRepository.class);
      assertThat(context).doesNotHaveBean(KnowledgeDocumentRepository.class);
    });
  }

  @Test
  void adaptersAreRegisteredAfterJpaDelegatesBecomeAvailable() {
    contextRunner.withUserConfiguration(DelegateConfiguration.class).run(context -> {
      assertThat(context).hasSingleBean(KnowledgeBaseRepository.class);
      assertThat(context).hasSingleBean(KnowledgeDocumentRepository.class);
    });
  }

  @Test
  void autoConfigurationIsRegisteredForBootDiscovery() throws Exception {
    try (var stream = getClass().getClassLoader().getResourceAsStream(
        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
      assertThat(stream).isNotNull();
      assertThat(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
          .contains(KnowledgeRepositoryAdapterAutoConfiguration.class.getName());
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class DelegateConfiguration {
    @Bean
    KnowledgeBaseJpaRepository knowledgeBaseJpaRepository() {
      return mock(KnowledgeBaseJpaRepository.class);
    }

    @Bean
    KnowledgeDocumentJpaRepository knowledgeDocumentJpaRepository() {
      return mock(KnowledgeDocumentJpaRepository.class);
    }
  }
}
