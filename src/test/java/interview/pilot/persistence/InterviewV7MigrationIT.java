package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InterviewV7MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v7");

  @Test
  void upgradesAnAppliedOriginalV6WithoutChecksumDriftThenCleanLatestAlsoMigrates() {
    Flyway v6 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("6").cleanDisabled(false).load();
    v6.migrate();
    JdbcTemplate jdbc = jdbc();
    Integer originalChecksum = jdbc.queryForObject(
        "select checksum from flyway_schema_history where version='6'", Integer.class);
    insertFixture(jdbc);

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("7")
        .cleanDisabled(false).load();
    latest.migrate();

    assertThat(jdbc.queryForObject(
        "select checksum from flyway_schema_history where version='6'", Integer.class))
        .isEqualTo(originalChecksum);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.table_constraints "
            + "where constraint_schema=database() and table_name='answer_attempt' "
            + "and constraint_name='chk_answer_attempt_status' and constraint_type='CHECK'",
        Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update(
        "insert into answer_attempt(request_id,session_id,turn_id,answer_hash,status) "
            + "values(uuid(),1,1,repeat('a',64),'ASKED')"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_answer_attempt_status");

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(7);
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
  }

  private void insertFixture(JdbcTemplate jdbc) {
    jdbc.update("insert into resume(resume_id,original_filename,content_hash,status) "
        + "values(uuid(),'a.txt',repeat('c',64),'READY')");
    jdbc.update("insert into job_profile(job_id,title,description_text) values(uuid(),'B','J')");
    jdbc.update("insert into interview_session(session_id,resume_id,job_profile_id,status,difficulty,"
        + "current_turn_no,total_turn_budget,provider_id,model_name,plan_snapshot) "
        + "values(uuid(),1,1,'INTERVIEWING','MEDIUM',1,5,'p','m',json_object())");
    jdbc.update("insert into interview_turn(session_id,turn_no,request_id,status,difficulty,"
        + "question_text,target_competency,answer_text,asked_at) "
        + "values(1,1,?,'PROCESSING','MEDIUM','Q','Java','answer',now(6))",
        UUID.randomUUID().toString());
  }
}
