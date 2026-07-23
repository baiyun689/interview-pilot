package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InterviewRagV14MigrationIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_rag_v14");

  private static DataSource dataSource;

  @BeforeAll
  static void migrateSchema() {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load()
        .migrate();
    dataSource = new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }

  @Test
  void v14AddsImmutableScopeAndTurnSnapshot() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      assertThat(columnExists(conn, "interview_session", "knowledge_scope_snapshot")).isTrue();
      assertThat(columnExists(conn, "interview_turn", "rag_context_snapshot")).isTrue();
      assertThat(columnExists(conn, "interview_turn", "rag_status")).isTrue();
      assertThat(tableExists(conn, "interview_knowledge_base")).isTrue();
    }
  }

  @Test
  void ragStatusDefaultsToNotConfigured() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      try (ResultSet rs = conn.getMetaData().getColumns(null, null, "interview_turn", "rag_status")) {
        assertThat(rs.next()).isTrue();
        String defaultValue = rs.getString("COLUMN_DEF");
        assertThat(defaultValue).asString().contains("NOT_CONFIGURED");
        assertThat(rs.getInt("NULLABLE")).isEqualTo(0); // NOT NULL
      }
    }
  }

  @Test
  void knowledgeScopeSnapshotIsJsonAndNullable() throws Exception {
    try (Connection conn = dataSource.getConnection()) {
      try (ResultSet rs = conn.getMetaData().getColumns(
          null, null, "interview_session", "knowledge_scope_snapshot")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("TYPE_NAME")).isEqualToIgnoringCase("JSON");
        assertThat(rs.getInt("NULLABLE")).isEqualTo(1); // nullable
      }
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
