package interview.pilot.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import interview.pilot.common.observability.AiMetrics;
import interview.pilot.knowledge.infrastructure.KnowledgeChunkRepository;
import interview.pilot.knowledge.retrieval.DefaultKnowledgeRanker;
import interview.pilot.knowledge.retrieval.HybridKnowledgeRetriever;
import interview.pilot.knowledge.retrieval.KeywordRetrievalSource;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.QdrantVectorRetrievalSource;
import interview.pilot.knowledge.retrieval.RetrievalSource;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class KnowledgeConfigurationTest {
  private static final String[] VALID_PROPERTIES = {
      "app.knowledge.enabled=false",
      "app.knowledge.files-root=./data/knowledge",
      "app.knowledge.chunk-size=800",
      "app.knowledge.chunk-overlap=100",
      "app.knowledge.batch-size=32",
      "app.knowledge.collection-name=knowledge_chunks_v1",
      "app.knowledge.top-k=5",
      "app.knowledge.similarity-threshold=0.72",
      "app.knowledge.qdrant.host=localhost",
      "app.knowledge.qdrant.port=6334",
      "app.knowledge.embedding.base-url=https://dashscope.aliyuncs.com/compatible-mode",
      "app.knowledge.embedding.api-key=",
      "app.knowledge.embedding.model=text-embedding-v3",
      "app.knowledge.embedding.dimensions=1024"
  };

  @Test
  void omittedRetrievalModeDefaultsToVectorAndOnlyWiresVectorSource() {
    retrievalContextRunner("app.knowledge.enabled=true").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(KnowledgeProperties.class).retrieval())
          .isEqualTo(KnowledgeProperties.RetrievalMode.VECTOR);
      assertThat(context).hasSingleBean(KnowledgeRetriever.class);
      assertThat(context).hasSingleBean(RetrievalSource.class);
      assertThat(context).doesNotHaveBean(KeywordRetrievalSource.class);
    });
  }

  @Test
  void explicitVectorModeDoesNotWireKeywordSource() {
    retrievalContextRunner("app.knowledge.enabled=true", "app.knowledge.retrieval=vector")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(RetrievalSource.class);
          assertThat(context).doesNotHaveBean(KeywordRetrievalSource.class);
        });
  }

  @Test
  void hybridModeWiresBothSourcesAndOneAggregate() {
    retrievalContextRunner("app.knowledge.enabled=true", "app.knowledge.retrieval=hybrid")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(KnowledgeProperties.class).retrieval())
              .isEqualTo(KnowledgeProperties.RetrievalMode.HYBRID);
          assertThat(context.getBeansOfType(RetrievalSource.class)).hasSize(2);
          assertThat(context).hasSingleBean(KeywordRetrievalSource.class);
          assertThat(context).hasSingleBean(KnowledgeRetriever.class);
        });
  }

  @Test
  void disabledKnowledgeNeverWiresSourcesEvenWithHybridSelected() {
    retrievalContextRunner("app.knowledge.enabled=false", "app.knowledge.retrieval=hybrid")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(RetrievalSource.class);
          assertThat(context).hasSingleBean(KnowledgeRetriever.class);
        });
  }

  @Test
  void invalidRetrievalModeFailsBinding() {
    retrievalContextRunner("app.knowledge.retrieval=typo")
        .run(context -> assertThat(context).hasFailed());
  }

  private ApplicationContextRunner retrievalContextRunner(String... properties) {
    return new ApplicationContextRunner()
        .withUserConfiguration(KnowledgeAutoConfiguration.class, HybridKnowledgeRetriever.class,
            QdrantVectorRetrievalSource.class, KeywordRetrievalSource.class, DefaultKnowledgeRanker.class)
        .withBean(VectorStore.class, () -> mock(VectorStore.class))
        .withBean(AiMetrics.class, () -> mock(AiMetrics.class))
        .withBean(KnowledgeChunkRepository.class, () -> mock(KnowledgeChunkRepository.class))
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues(properties);
  }

  @Test
  void rejectsCollectionNameOtherThanKnowledgeChunksV1DuringBinding() {
    knowledgeContextRunner("app.knowledge.collection-name=another_collection")
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("Knowledge collection name must be knowledge_chunks_v1"));
  }

  @Test
  void rejectsEmbeddingModelOtherThanTextEmbeddingV3DuringBinding() {
    knowledgeContextRunner("app.knowledge.embedding.model=text-embedding-v2")
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("Knowledge embedding model must be text-embedding-v3"));
  }

  @Test
  void rejectsEmbeddingDimensionsOtherThan1024DuringBinding() {
    knowledgeContextRunner("app.knowledge.embedding.dimensions=1536")
        .run(context -> assertThat(context.getStartupFailure())
            .hasRootCauseMessage("Knowledge embedding dimensions must be 1024"));
  }

  @Test
  void disabledKnowledgeWithoutApiKeyCreatesNoKnowledgeInfrastructureBeans() {
    knowledgeContextRunner().run(context -> {
      assertThat(context).doesNotHaveBean("knowledgeEmbeddingModel");
      assertThat(context).doesNotHaveBean("knowledgeQdrantClient");
      assertThat(context).doesNotHaveBean("knowledgeVectorStore");
    });
  }

  @Test
  void enabledKnowledgeUsesNamedEmbeddingQdrantAndVectorStoreBeansWithoutConnectingToQdrant() {
    new ApplicationContextRunner()
        .withUserConfiguration(ExistingQdrantClientConfiguration.class, EmbeddingConfiguration.class)
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues("app.knowledge.enabled=true", "app.knowledge.embedding.api-key=test-key")
        .run(context -> {
          assertThat(context).hasSingleBean(EmbeddingModel.class);
          assertThat(context).hasSingleBean(VectorStore.class);
          assertThat(context).hasBean("knowledgeEmbeddingModel");
          assertThat(context).hasBean("knowledgeQdrantClient");
          assertThat(context).hasBean("knowledgeVectorStore");

          var qdrantClient = context.getBean("knowledgeQdrantClient", QdrantClient.class);
          var vectorStore = context.getBean("knowledgeVectorStore", VectorStore.class);
          assertThat(vectorStore.getNativeClient()).contains(qdrantClient);
          verify(qdrantClient).listCollectionsAsync();
        });
  }

  private ApplicationContextRunner knowledgeContextRunner(String... properties) {
    return new ApplicationContextRunner()
        .withUserConfiguration(EmbeddingConfiguration.class)
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues(properties);
  }

  @Configuration(proxyBeanMethods = false)
  static class ExistingQdrantClientConfiguration {
    @Bean("knowledgeQdrantClient")
    QdrantClient knowledgeQdrantClient() {
      var client = mock(QdrantClient.class);
      when(client.listCollectionsAsync()).thenReturn(Futures.immediateFuture(List.of("knowledge_chunks_v1")));
      return client;
    }
  }
}
