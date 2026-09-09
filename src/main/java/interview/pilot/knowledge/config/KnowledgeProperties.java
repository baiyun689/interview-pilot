package interview.pilot.knowledge.config;

import java.net.URI;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.knowledge")
public record KnowledgeProperties(
    boolean enabled,
    Path filesRoot,
    int chunkSize,
    int chunkOverlap,
    int batchSize,
    String collectionName,
    int topK,
    double similarityThreshold,
    int candidateCount,
    int contextCharacterBudget,
    Qdrant qdrant,
    Embedding embedding,
    RetrievalMode retrieval) {

  @ConstructorBinding
  public KnowledgeProperties {
    if (retrieval == null) retrieval = RetrievalMode.VECTOR;
    if (filesRoot == null) {
      throw new IllegalArgumentException("Knowledge files root is required");
    }
    if (chunkSize < 1) {
      throw new IllegalArgumentException("Knowledge chunk size must be positive");
    }
    if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
      throw new IllegalArgumentException("Knowledge chunk overlap must be non-negative and smaller than chunk size");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("Knowledge batch size must be positive");
    }
    if (!"knowledge_chunks_v1".equals(collectionName)) {
      throw new IllegalArgumentException("Knowledge collection name must be knowledge_chunks_v1");
    }
    if (topK < 1) {
      throw new IllegalArgumentException("Knowledge top-K must be positive");
    }
    if (candidateCount < topK) candidateCount = Math.max(12, topK * 3);
    if (contextCharacterBudget < 1) contextCharacterBudget = 6_000;
    if (similarityThreshold < 0 || similarityThreshold > 1) {
      throw new IllegalArgumentException("Knowledge similarity threshold must be between zero and one");
    }
    if (qdrant == null) {
      throw new IllegalArgumentException("Qdrant configuration is required");
    }
    if (embedding == null) {
      throw new IllegalArgumentException("Knowledge embedding configuration is required");
    }
  }

  public KnowledgeProperties(
      boolean enabled, Path filesRoot, int chunkSize, int chunkOverlap, int batchSize,
      String collectionName, int topK, double similarityThreshold,
      int candidateCount, int contextCharacterBudget, Qdrant qdrant, Embedding embedding) {
    this(enabled, filesRoot, chunkSize, chunkOverlap, batchSize, collectionName,
        topK, similarityThreshold, candidateCount, contextCharacterBudget, qdrant, embedding,
        RetrievalMode.VECTOR);
  }

  public enum RetrievalMode { VECTOR, HYBRID }

  public KnowledgeProperties(
      boolean enabled, Path filesRoot, int chunkSize, int chunkOverlap, int batchSize,
      String collectionName, int topK, double similarityThreshold,
      Qdrant qdrant, Embedding embedding) {
    this(enabled, filesRoot, chunkSize, chunkOverlap, batchSize, collectionName,
        topK, similarityThreshold, Math.max(12, topK * 3), 6_000, qdrant, embedding);
  }

  public static KnowledgeProperties testDefaults(
      int topK, int candidateCount, double threshold, int contextCharacterBudget) {
    return new KnowledgeProperties(
        true, Path.of("./build/tmp/knowledge"), 800, 100, 32, "knowledge_chunks_v1",
        topK, threshold, candidateCount, contextCharacterBudget,
        new Qdrant("localhost", 6334),
        new Embedding(URI.create("http://localhost"), "test", "text-embedding-v3", 1024));
  }

  public record Qdrant(String host, int port) {
    public Qdrant {
      if (!hasText(host)) {
        throw new IllegalArgumentException("Qdrant host is required");
      }
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("Qdrant port must be valid");
      }
    }
  }

  public record Embedding(URI baseUrl, String apiKey, String model, int dimensions) {
    public Embedding {
      if (baseUrl == null) {
        throw new IllegalArgumentException("Embedding base URL is required");
      }
      if (!"text-embedding-v3".equals(model)) {
        throw new IllegalArgumentException("Knowledge embedding model must be text-embedding-v3");
      }
      if (dimensions != 1024) {
        throw new IllegalArgumentException("Knowledge embedding dimensions must be 1024");
      }
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
