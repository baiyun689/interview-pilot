package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InterviewV4MigrationIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_v4_migration");

  @Test
  void refusesLegacyInterviewRowsBeforeAnyAlterThenSucceedsOnceTablesAreEmpty() {
    Flyway flyway = flyway();
    flyway.getConfiguration();
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("3")
        .load()
        .migrate();
    JdbcTemplate jdbc = jdbcTemplate();
    insertLegacyFixture(jdbc);

    assertThatThrownBy(() -> flyway.migrate())
        .hasMessageContaining("V4_REQUIRES_EMPTY_INTERVIEW_TABLES");
    assertThat(columnCount(jdbc, "interview_session", "provider_id")).isZero();
    assertThat(columnCount(jdbc, "interview_turn", "target_competency")).isZero();
    assertThat(jdbc.queryForObject("select count(*) from interview_session", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from interview_turn", Integer.class))
        .isEqualTo(1);

    jdbc.update("delete from interview_turn");
    jdbc.update("delete from interview_session");
    jdbc.update("delete from job_profile");
    jdbc.update("delete from resume");
    flyway.repair();
    flyway.migrate();

    assertThat(columnCount(jdbc, "interview_session", "provider_id")).isEqualTo(1);
    assertThat(columnCount(jdbc, "interview_session", "plan_snapshot")).isEqualTo(1);
    assertThat(columnCount(jdbc, "interview_turn", "target_competency")).isEqualTo(1);
  }

  private Flyway flyway() {
    return Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load();
  }

  private JdbcTemplate jdbcTemplate() {
    return new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
  }

  private void insertLegacyFixture(JdbcTemplate jdbc) {
    jdbc.update("insert into resume "
        + "(resume_id,original_filename,content_hash,parsed_text,skills_snapshot,status) "
        + "values (uuid(),'candidate.txt',repeat('a',64),'Java',json_object(),'READY')");
    jdbc.update("insert into job_profile "
        + "(job_id,title,description_text,requirements_snapshot) "
        + "values (uuid(),'Backend Engineer','Java',json_object())");
    jdbc.update("insert into interview_session "
        + "(session_id,resume_id,job_profile_id,status,difficulty,current_turn_no) "
        + "values (uuid(),1,1,'INTERVIEWING','MEDIUM',1)");
    jdbc.update("insert into interview_turn "
        + "(session_id,turn_no,request_id,status,difficulty,question_text,asked_at) "
        + "values (1,1,uuid(),'ASKED','MEDIUM','Explain Java',now(6))");
  }

  private int columnCount(JdbcTemplate jdbc, String table, String column) {
    return jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name=? and column_name=?",
        Integer.class, table, column);
  }
}
