package interview.pilot.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/** Actual MySQL ngram matching and Hibernate native parameter/entity mapping, without an LLM. */
@Testcontainers
class KnowledgeChunkSearchRepositoryIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("knowledge_keyword_it");
  private static EntityManagerFactory factory;
  private EntityManager entityManager;
  private KnowledgeChunkSearchRepository repository;
  private final UUID user = UUID.randomUUID();
  private final UUID baseA = UUID.randomUUID();
  private final UUID baseB = UUID.randomUUID();
  private final UUID docA = UUID.randomUUID();
  private final UUID docB = UUID.randomUUID();
  private int chunkIndex;

  @BeforeAll
  static void schema() {
    Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load().migrate();
    factory = new Configuration().addAnnotatedClass(KnowledgeChunkEntity.class)
        .setProperty("hibernate.connection.url", MYSQL.getJdbcUrl())
        .setProperty("hibernate.connection.username", MYSQL.getUsername())
        .setProperty("hibernate.connection.password", MYSQL.getPassword())
        .setProperty("hibernate.hbm2ddl.auto", "validate")
        .buildSessionFactory();
  }

  @AfterAll
  static void closeFactory() {
    if (factory != null) factory.close();
  }

  @BeforeEach
  void setUp() {
    entityManager = factory.createEntityManager();
    repository = new KnowledgeChunkSearchRepositoryImpl(entityManager);
    entityManager.getTransaction().begin();
    entityManager.createNativeQuery("DELETE FROM knowledge_chunk").executeUpdate();
    entityManager.getTransaction().commit();
  }

  @AfterEach
  void closeSession() {
    if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
    entityManager.close();
  }

  @Test
  void matchesChineseAndPreservesExactUserBaseAndDocumentRevisionPairs() {
    var allowedA = row(user, baseA, docA, 1, "事务传播与独立事务的边界");
    var allowedB = row(user, baseB, docB, 2, "事务传播与嵌套保存点");
    persist(allowedA, allowedB,
        row(UUID.randomUUID(), baseA, docA, 1, "事务传播其他用户"),
        row(user, UUID.randomUUID(), docA, 1, "事务传播其他知识库"),
        row(user, baseA, UUID.randomUUID(), 1, "事务传播未选择文档"),
        row(user, baseA, docA, 2, "事务传播错误版本A"),
        row(user, baseB, docB, 1, "事务传播错误版本B"));

    var results = repository.search(scope(), "事务传播", 20);

    assertThat(results).extracting(KnowledgeChunkEntity::getPointId)
        .containsExactlyInAnyOrder(allowedA.getPointId(), allowedB.getPointId());
    assertThat(results).allSatisfy(row -> {
      assertThat(row.getUserId()).isEqualTo(user);
      assertThat(row.getFilename()).isEqualTo("notes.md");
      assertThat(row.getSection()).startsWith("chunk:");
    });
    // Real SQL LIMIT, including dynamic OR groups and multiple bound KB parameters.
    assertThat(repository.search(scope(), "事务传播", 1)).hasSize(1);
  }

  @Test
  void matchesTechnicalKeywordsAndOrdersEqualScoresDeterministically() {
    var first = row(user, baseA, docA, 1, "REQUIRES_NEW 独立事务挂起");
    var second = row(user, baseA, docA, 1, "REQUIRES_NEW 独立事务挂起");
    first.setPointId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    second.setPointId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    persist(second, first, row(user, baseA, docA, 1, "数据库缓冲池刷新"));
    assertThat(repository.search(scope(), "REQUIRES_NEW", 10))
        .extracting(KnowledgeChunkEntity::getPointId)
        .containsExactly(first.getPointId(), second.getPointId());
  }

  @Test
  void unmatchedAndSqlShapedInputCannotBypassScope() {
    persist(row(UUID.randomUUID(), baseA, docA, 1, "事务传播"),
        row(user, baseA, docA, 1, "数据库缓冲池刷新"));
    assertThat(repository.search(scope(), "不存在的术语xyzzy", 10)).isEmpty();
    assertThat(repository.search(scope(), "事务传播' OR 1=1 --", 10)).isEmpty();
  }

  @Test
  void invalidInputsCannotProduceUnboundedQuery() {
    assertThatThrownBy(() -> repository.search(null, "事务", 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.search(scope(), " ", 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.search(scope(), "事务", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void organizationAndPersonalScopesCannotReadEachOthersChunks() {
    var companyA = row(null, baseA, docA, 1, "事务传播企业资料"); companyA.setOrganizationId(101L);
    var companyB = row(null, baseA, docA, 1, "事务传播企业资料"); companyB.setOrganizationId(102L);
    var personal = row(user, baseA, docA, 1, "事务传播个人资料");
    persist(companyA, companyB, personal);
    var scopeA = new ValidatedKnowledgeScope(null, List.of(baseA), List.of(new ValidatedKnowledgeScope.DocumentRevision(docA, 1)), "v3", 101L);
    assertThat(repository.search(scopeA, "事务传播", 10)).extracting(KnowledgeChunkEntity::getPointId).containsExactly(companyA.getPointId());
    assertThat(repository.search(scope(), "事务传播", 10)).extracting(KnowledgeChunkEntity::getPointId).containsExactly(personal.getPointId());
    assertThatThrownBy(() -> new ValidatedKnowledgeScope(user, List.of(baseA), scopeA.documents(), "v3", 101L)).isInstanceOf(IllegalArgumentException.class);
  }

  private ValidatedKnowledgeScope scope() {
    return new ValidatedKnowledgeScope(user, List.of(baseA, baseB), List.of(
        new ValidatedKnowledgeScope.DocumentRevision(docA, 1),
        new ValidatedKnowledgeScope.DocumentRevision(docB, 2)), "v3");
  }

  private KnowledgeChunkEntity row(UUID owner, UUID base, UUID document, int revision, String text) {
    int index = chunkIndex++;
    return KnowledgeChunkEntity.of(UUID.randomUUID(), owner, base, document, revision, index,
        "chunk:" + index, "notes.md", text);
  }

  private void persist(KnowledgeChunkEntity... rows) {
    entityManager.getTransaction().begin();
    for (var row : rows) entityManager.persist(row);
    // InnoDB FULLTEXT indexes become searchable after commit.
    entityManager.getTransaction().commit();
    entityManager.clear();
  }
}
