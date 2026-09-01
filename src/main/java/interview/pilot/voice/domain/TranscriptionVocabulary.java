package interview.pilot.voice.domain;

import java.util.List;

/**
 * Fixed Java-backend vocabulary hints (plan §10): ASR hotword candidates for a spoken answer.
 * The list is deliberately modest — vocabulary is a hint channel, never an instruction, and a
 * provider that cannot honor it must ignore it silently.
 */
public final class TranscriptionVocabulary {

  public static final List<String> JAVA_BACKEND_TERMS = List.of(
      "Java", "Spring", "Spring Boot", "Spring Cloud", "MyBatis", "JVM", "并发",
      "线程", "锁", "事务", "索引", "MySQL", "Redis", "RabbitMQ", "Kafka",
      "消息队列", "分布式", "微服务", "Docker", "Kubernetes", "Nginx",
      "Elasticsearch", "缓存", "幂等", "重试", "高并发", "性能优化", "设计模式",
      "面向对象", "接口");

  private TranscriptionVocabulary() {}
}
