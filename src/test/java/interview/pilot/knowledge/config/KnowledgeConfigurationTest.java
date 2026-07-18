package interview.pilot.knowledge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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
