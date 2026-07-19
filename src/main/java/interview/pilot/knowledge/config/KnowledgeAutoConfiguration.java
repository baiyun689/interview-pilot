package interview.pilot.knowledge.config;

import interview.pilot.knowledge.storage.FileSystemKnowledgeDocumentStore;
import interview.pilot.knowledge.storage.KnowledgeDocumentStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeAutoConfiguration {

  @Bean
  @ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
  KnowledgeDocumentStore knowledgeDocumentStore(KnowledgeProperties properties) {
    return new FileSystemKnowledgeDocumentStore(properties.filesRoot());
  }
}
