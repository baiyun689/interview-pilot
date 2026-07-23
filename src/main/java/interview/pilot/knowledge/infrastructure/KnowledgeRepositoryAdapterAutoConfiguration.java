package interview.pilot.knowledge.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KnowledgeRepositoryAdapterAutoConfiguration {
  @Bean
  KnowledgeBaseRepository knowledgeBaseRepository(KnowledgeBaseJpaRepository delegate) {
    return new KnowledgeBaseRepositoryAdapter(delegate);
  }

  @Bean
  KnowledgeDocumentRepository knowledgeDocumentRepository(KnowledgeDocumentJpaRepository delegate) {
    return new KnowledgeDocumentRepositoryAdapter(delegate);
  }
}
