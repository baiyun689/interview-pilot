package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class UserOwnershipV11MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v11");

  @Test
  void v11BackfillsLegacyOwnerAndScopesResumeHash() {
    Flyway v10 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("10").load();
    v10.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    jdbc.update("insert into resume(resume_id,original_filename,content_hash,status) "
        + "values(uuid(),'legacy.pdf',repeat('a',64),'PENDING')");

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load();
    latest.migrate();

    Integer users = jdbc.queryForObject(
        "select count(*) from user_account where email = 'legacy-demo@invalid.local'",
        Integer.class);
    Integer missingOwners = jdbc.queryForObject(
        "select count(*) from resume where user_account_id is null", Integer.class);
    assertThat(users).isEqualTo(1);
    assertThat(missingOwners).isZero();
    assertThat(indexExists(jdbc, "resume", "uq_resume_user_hash")).isTrue();
  }

  private boolean indexExists(JdbcTemplate jdbc, String table, String index) {
    Integer count = jdbc.queryForObject(
        "select count(*) from information_schema.statistics where table_schema = database() "
            + "and table_name = ? and index_name = ?",
        Integer.class, table, index);
    return count != null && count > 0;
  }
}
