package interview.pilot.async.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;

/**
 * Context-level check that Spring component scanning really collects every policy
 * {@code @Component} into the registry: a new policy class that forgets {@code @Component}
 * (or a new enum constant without any policy) fails here at test time instead of at runtime.
 */
class RetryableTaskPolicyRegistryContextTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(PolicyScanConfiguration.class);

  @Test
  void springCollectsEveryPolicyComponentIntoTheRegistry() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      var registry = context.getBean(RetryableTaskPolicyRegistry.class);
      assertThat(registry.registeredTypes())
          .containsExactlyInAnyOrder(AsyncTaskType.values());
      assertThat(context.getBeansOfType(RetryableTaskPolicy.class)).hasSize(8);
    });
  }

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = RetryableTaskPolicy.class)
  static class PolicyScanConfiguration {
    @Bean
    ResumeRepository resumeRepository() {
      return mock(ResumeRepository.class);
    }

    @Bean
    InterviewSessionRepository interviewSessionRepository() {
      return mock(InterviewSessionRepository.class);
    }

    @Bean
    KnowledgeDocumentRepository knowledgeDocumentRepository() {
      return mock(KnowledgeDocumentRepository.class);
    }
  }
}
