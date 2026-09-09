package interview.pilot.knowledge.infrastructure;

import interview.pilot.knowledge.indexing.KnowledgeRevisionCandidates;
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

  @Bean
  KnowledgeChunkRepository knowledgeChunkRepository(KnowledgeChunkJpaRepository delegate) {
    return new KnowledgeChunkRepositoryAdapter(delegate);
  }

  @Bean
  KnowledgeRevisionCandidates knowledgeRevisionCandidates(KnowledgeDocumentJpaRepository delegate) {
    return () -> delegate.findRevisionCleanupCandidates().stream()
        .map(document -> new KnowledgeRevisionCandidates.Candidate(
            document.getDocumentId(), document.getActiveIndexRevision(),
            document.getIndexRevision()))
        .toList();
  }
}
