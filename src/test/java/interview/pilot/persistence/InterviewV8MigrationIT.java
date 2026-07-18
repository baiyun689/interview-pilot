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
class InterviewV8MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v8");

  @Test
  void upgradesV7WithoutChecksumDriftKeepsLegacyReportsAndCleanLatestMigrates() {
    Flyway v7 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("7").cleanDisabled(false).load();
    v7.migrate();
    JdbcTemplate jdbc = jdbc();
    Integer v7Checksum = jdbc.queryForObject(
        "select checksum from flyway_schema_history where version='7'", Integer.class);
    insertLegacyReport(jdbc);

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .cleanDisabled(false).load();
    latest.migrate();

    assertThat(jdbc.queryForObject(
        "select checksum from flyway_schema_history where version='7'", Integer.class))
        .isEqualTo(v7Checksum);
    assertThat(jdbc.queryForObject(
        "select count(*) from interview_report where report_snapshot is null", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select data_type from information_schema.columns where table_schema=database() "
            + "and table_name='interview_report' and column_name='report_snapshot'", String.class))
        .isEqualTo("json");

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(9);
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
  }

  private void insertLegacyReport(JdbcTemplate jdbc) {
    jdbc.update("insert into resume(resume_id,original_filename,content_hash,status) "
        + "values(uuid(),'a.txt',repeat('a',64),'READY')");
    jdbc.update("insert into job_profile(job_id,title,description_text) values(uuid(),'B','J')");
    jdbc.update("insert into interview_session(session_id,resume_id,job_profile_id,status,difficulty,"
        + "current_turn_no,total_turn_budget,provider_id,model_name,plan_snapshot) "
        + "values(uuid(),1,1,'COMPLETED','MEDIUM',1,5,'p','m',json_object())");
    jdbc.update("insert into interview_report(report_id,session_id,summary_text,feedback_text,"
        + "score_snapshot) values(uuid(),1,'legacy','legacy',json_object('overallScore',80))");
  }
}
