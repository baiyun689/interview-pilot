package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class InterviewRagV14MigrationIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_rag_v14");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired
  private DataSource dataSource;

  @Test
  void v14AddsImmutableScopeAndTurnSnapshot() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      assertThat(columnExists(conn, "interview_session", "knowledge_scope_snapshot")).isTrue();
      assertThat(columnExists(conn, "interview_turn", "rag_context_snapshot")).isTrue();
      assertThat(columnExists(conn, "interview_turn", "rag_status")).isTrue();
      assertThat(tableExists(conn, "interview_knowledge_base")).isTrue();
    }
  }

  private static boolean columnExists(Connection conn, String table, String column) throws Exception {
    var rs = conn.getMetaData().getColumns(null, null, table, column);
    return rs.next();
  }

  private static boolean tableExists(Connection conn, String table) throws Exception {
    var rs = conn.getMetaData().getTables(null, null, table, null);
    return rs.next();
  }
}
