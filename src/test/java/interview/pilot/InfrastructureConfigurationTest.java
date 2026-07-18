package interview.pilot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InfrastructureConfigurationTest {
  @Test
  @DisplayName("共享环境变量保持应用与 Compose 宿主端口一致")
  void sharedEnvironmentKeepsApplicationAndComposeHostPortsAligned() throws IOException {
    var application = read("src/main/resources/application.yml");
    var compose = read("docker-compose.yml");
    var environmentExample = read(".env.example");

    assertTrue(application.contains("${MYSQL_PORT:3306}"));
    assertTrue(application.contains("${REDIS_PORT:6379}"));
    assertTrue(application.contains("${RABBITMQ_PORT:5672}"));
    assertTrue(compose.contains("\"${MYSQL_PORT:-3306}:3306\""));
    assertTrue(compose.contains("\"${REDIS_PORT:-6379}:6379\""));
    assertTrue(compose.contains("\"${RABBITMQ_PORT:-5672}:5672\""));
    assertTrue(compose.contains("\"${RABBITMQ_MANAGEMENT_PORT:-15672}:15672\""));
    assertTrue(environmentExample.contains("RABBITMQ_MANAGEMENT_PORT=15672"));
    assertFalse(application.contains("REDIS_PASSWORD"));
    assertFalse(environmentExample.contains("REDIS_PASSWORD"));
  }

  @Test
  @DisplayName("Servlet multipart 边界允许 10MB 简历并为请求封装预留空间")
  void multipartBoundaryMatchesResumeUploadLimit() throws IOException {
    var application = read("src/main/resources/application.yml");

    assertTrue(application.contains("max-file-size: 10MB"));
    assertTrue(application.contains("max-request-size: 11MB"));
  }

  @Test
  @DisplayName("知识库基础设施声明 Qdrant、文件卷和 DashScope Embedding 配置")
  void knowledgeInfrastructureDeclaresQdrantFileVolumeAndEmbeddingConfiguration() throws IOException {
    var compose = read("docker-compose.yml");
    var application = read("src/main/resources/application.yml");
    var environmentExample = read(".env.example");

    assertTrue(compose.contains("qdrant/qdrant:"));
    assertTrue(compose.contains("knowledge_files:"));
    assertTrue(application.contains("collection-name: knowledge_chunks_v1"));
    assertTrue(application.contains("dimensions: 1024"));
    assertTrue(environmentExample.contains("DASHSCOPE_EMBEDDING_MODEL=text-embedding-v3"));
    assertFalse(compose.contains("KNOWLEDGE_COLLECTION_NAME"));
    assertFalse(environmentExample.contains("KNOWLEDGE_COLLECTION_NAME"));
  }

  private String read(String path) throws IOException {
    return Files.readString(Path.of(path));
  }
}
