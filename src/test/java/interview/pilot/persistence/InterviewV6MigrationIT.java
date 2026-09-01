package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
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
class InterviewV6MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v6");

  @Test
  void backfillsExistingTerminalAndProcessingKeysAndEnforcesGlobalUniqueness() {
    Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("5").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    fixture(jdbc);
    Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("7").load().migrate();

    assertThat(jdbc.queryForList(
        "select status from answer_attempt order by turn_id", String.class))
        .containsExactly("PROCESSING", "COMPLETED", "FAILED");
    assertThat(jdbc.queryForObject(
        "select answer_hash from answer_attempt where status='COMPLETED'", String.class))
        .hasSize(64);
    String duplicate = jdbc.queryForObject(
        "select request_id from answer_attempt limit 1", String.class);
    assertThatThrownBy(() -> jdbc.update(
        "insert into answer_attempt(request_id,session_id,turn_id,answer_hash,status) "
            + "select ?,session_id,turn_id,answer_hash,status from answer_attempt limit 1", duplicate))
        .isInstanceOf(DuplicateKeyException.class);
    assertThatThrownBy(() -> jdbc.update(
        "insert into answer_attempt(request_id,session_id,turn_id,answer_hash,status) "
            + "select uuid(),session_id,turn_id,answer_hash,'ASKED' from answer_attempt limit 1"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .hasMessageContaining("chk_answer_attempt_status");
  }

  private void fixture(JdbcTemplate jdbc) {
    jdbc.update("insert into resume(resume_id,original_filename,content_hash,status) "
        + "values(uuid(),'a.txt',repeat('b',64),'READY')");
    jdbc.update("insert into job_profile(job_id,title,description_text) values(uuid(),'B','J')");
    jdbc.update("insert into interview_session(session_id,resume_id,job_profile_id,status,difficulty,"
        + "current_turn_no,total_turn_budget,provider_id,model_name,plan_snapshot) "
        + "values(uuid(),1,1,'INTERVIEWING','MEDIUM',3,5,'p','m',json_object())");
    for (int turn = 1; turn <= 3; turn++) {
      String status = turn == 1 ? "PROCESSING" : turn == 2 ? "COMPLETED" : "FAILED";
      jdbc.update("insert into interview_turn(session_id,turn_no,request_id,status,difficulty,"
          + "question_text,target_competency,answer_text,asked_at) "
          + "values(1,?,?,?,'MEDIUM','Q','Java',' answer ',now(6))",
          turn, UUID.randomUUID().toString(), status);
    }
  }
}
