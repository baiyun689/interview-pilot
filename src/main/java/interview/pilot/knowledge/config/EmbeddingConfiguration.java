package interview.pilot.knowledge.config;

import io.micrometer.observation.ObservationRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class EmbeddingConfiguration {
  @Bean
  @ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
  EmbeddingModel knowledgeEmbeddingModel(KnowledgeProperties properties) {
    var embedding = properties.embedding();
    Assert.hasText(embedding.apiKey(), "Knowledge embedding API key is required when knowledge is enabled");

    var openAiApi = OpenAiApi.builder()
        .baseUrl(embedding.baseUrl().toString())
        .apiKey(embedding.apiKey())
        .build();
    var options = OpenAiEmbeddingOptions.builder()
        .model(embedding.model())
        .dimensions(1024)
        .build();

    return new OpenAiEmbeddingModel(
        openAiApi, MetadataMode.EMBED, options,
        RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
  QdrantClient knowledgeQdrantClient(KnowledgeProperties properties) {
    var qdrant = properties.qdrant();
    return new QdrantClient(QdrantGrpcClient.newBuilder(qdrant.host(), qdrant.port(), false).build());
  }

  @Bean
  @ConditionalOnProperty(prefix = "app.knowledge", name = "enabled", havingValue = "true")
  VectorStore knowledgeVectorStore(
      @Qualifier("knowledgeQdrantClient") QdrantClient knowledgeQdrantClient,
      @Qualifier("knowledgeEmbeddingModel") EmbeddingModel knowledgeEmbeddingModel,
      KnowledgeProperties properties) {
    return QdrantVectorStore.builder(knowledgeQdrantClient, knowledgeEmbeddingModel)
        .collectionName(properties.collectionName())
        .initializeSchema(true)
        .build();
  }
}
