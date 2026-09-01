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
class AsyncTaskV9MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v9");

  @Test
  void upgradesV8WithoutChecksumDriftAndDefaultsExistingTaskEpochToZero() {
    Flyway v8 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("8").load();
    v8.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    Integer checksum = jdbc.queryForObject(
        "select checksum from flyway_schema_history where version='8'", Integer.class);
    jdbc.update("insert into async_task(task_id,task_type,biz_key,status,payload_snapshot) "
        + "values(uuid(),'RESUME_ANALYSIS','resume:1','PENDING',json_object('resumeId',1))");

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .cleanDisabled(false).load();
    latest.migrate();

    assertThat(jdbc.queryForObject(
        "select checksum from flyway_schema_history where version='8'", Integer.class))
        .isEqualTo(checksum);
    assertThat(jdbc.queryForObject(
        "select execution_epoch from async_task limit 1", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select is_nullable from information_schema.columns where table_schema=database() "
            + "and table_name='async_task' and column_name='execution_epoch'", String.class))
        .isEqualTo("NO");

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(21);
  }
}
