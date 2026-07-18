package interview.pilot.knowledge.infrastructure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {
    HibernateJpaAutoConfiguration.class,
    DataJpaRepositoriesAutoConfiguration.class
})
@ConditionalOnBean({KnowledgeBaseJpaRepository.class, KnowledgeDocumentJpaRepository.class})
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
