package interview.pilot.knowledge.config;

import java.net.URI;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
    Qdrant qdrant,
    Embedding embedding) {

  public KnowledgeProperties {
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
    if (!hasText(collectionName)) {
      throw new IllegalArgumentException("Knowledge collection name is required");
    }
    if (topK < 1) {
      throw new IllegalArgumentException("Knowledge top-K must be positive");
    }
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
      if (!hasText(model)) {
        throw new IllegalArgumentException("Embedding model is required");
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
