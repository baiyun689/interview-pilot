package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class KnowledgeSchemaV13MigrationIT {
  @Container
  static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_knowledge_v13");

  @Test
  void knowledgeTablesEnforceTenantScopedHashesAndStatuses() {
    Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));

    assertThat(tableExists(jdbc, "knowledge_base")).isTrue();
    assertThat(tableExists(jdbc, "knowledge_document")).isTrue();
    assertThat(indexExists(jdbc, "knowledge_document", "uq_knowledge_document_base_hash")).isTrue();
    assertThat(indexExists(jdbc, "knowledge_document", "idx_knowledge_document_user_base_status"))
        .isTrue();
    assertThat(constraintExists(jdbc, "knowledge_base", "chk_knowledge_base_status")).isTrue();
    assertThat(constraintExists(jdbc, "knowledge_document", "chk_knowledge_document_status"))
        .isTrue();
    assertThat(constraintExists(jdbc, "knowledge_document", "fk_knowledge_document_base"))
        .isTrue();

    insertUser(jdbc, "owner-one@invalid.local");
    insertUser(jdbc, "owner-two@invalid.local");
    jdbc.update("insert into knowledge_base(user_account_id, knowledge_base_id, name, status) "
        + "values(2, uuid(), 'One', 'ACTIVE')");
    jdbc.update("insert into knowledge_base(user_account_id, knowledge_base_id, name, status) "
        + "values(3, uuid(), 'Two', 'ACTIVE')");
    jdbc.update("insert into knowledge_document(knowledge_base_id, document_id, original_filename, "
        + "content_hash, storage_key, status) values(1, uuid(), 'first.txt', repeat('a', 64), "
        + "'owner-one/first.txt', 'PENDING')");
    jdbc.update("insert into knowledge_document(knowledge_base_id, document_id, original_filename, "
        + "content_hash, storage_key, status) values(2, uuid(), 'second.txt', repeat('a', 64), "
        + "'owner-two/second.txt', 'PENDING')");

    assertThatThrownBy(() -> jdbc.update("insert into knowledge_document(knowledge_base_id, "
        + "document_id, original_filename, content_hash, storage_key, status) values(1, uuid(), "
        + "'duplicate.txt', repeat('a', 64), 'owner-one/duplicate.txt', 'PENDING')"))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("uq_knowledge_document_base_hash");
  }

  private boolean tableExists(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject("select count(*) from information_schema.tables "
        + "where table_schema=database() and table_name=?", Integer.class, table) == 1;
  }

  private boolean indexExists(JdbcTemplate jdbc, String table, String index) {
    return jdbc.queryForObject("select count(*) from information_schema.statistics "
        + "where table_schema=database() and table_name=? and index_name=?", Integer.class, table, index) > 0;
  }

  private boolean constraintExists(JdbcTemplate jdbc, String table, String constraint) {
    return jdbc.queryForObject("select count(*) from information_schema.table_constraints "
        + "where constraint_schema=database() and table_name=? and constraint_name=?", Integer.class,
        table, constraint) == 1;
  }

  private void insertUser(JdbcTemplate jdbc, String email) {
    jdbc.update("insert into user_account(user_id, email, password_hash, display_name, status) "
        + "values(uuid(), ?, '!', 'Knowledge Owner', 'ACTIVE')", email);
  }
}
