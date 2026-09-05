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
class InterviewV23MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v23");

  @Test
  void renamesAnswerHashToSubmissionFingerprintPreservingValues() {
    Flyway v22 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("22").cleanDisabled(false).load();
    v22.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    jdbc.update("insert into user_account(user_id,email,password_hash,display_name,status) "
        + "values(uuid(),'v23-it@example.com','!','V23','ACTIVE')");
    jdbc.update("insert into interview_session(user_account_id,session_id,status,difficulty,"
        + "interview_size,job_source_type,job_title,current_turn_no,current_main_question_no,"
        + "total_main_question_count,provider_id,model_name,brief_snapshot) "
        + "values(1,uuid(),'INTERVIEWING','MEDIUM','STANDARD','CUSTOM','Java 后端',1,1,9,"
        + "'dashscope','qwen',json_object())");
    jdbc.update("insert into interview_question_card(session_id,phase,phase_sequence,topic,"
        + "question_text,focus_points,grounding_mode,rag_status,rag_context_snapshot,source_ids,"
        + "follow_up_quota,fallback_follow_up) "
        + "values(1,'FUNDAMENTALS',1,'并发','请解释并发问题','[]','GENERAL','NOT_REQUESTED',"
        + "'{}','[]',1,'如果超时你会怎么办？')");
    jdbc.update("insert into interview_turn(session_id,turn_no,phase,question_type,"
        + "source_card_id,status,question_text,asked_at) "
        + "values(1,1,'FUNDAMENTALS','MAIN',1,'COMPLETED','并发问题',now(6))");
    jdbc.update("insert into answer_attempt(request_id,session_id,turn_id,answer_hash,status) "
        + "values(uuid(),1,1,repeat('a',64),'COMPLETED')");
    String preserved = jdbc.queryForObject(
        "select answer_hash from answer_attempt limit 1", String.class);

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .cleanDisabled(false).load();
    latest.migrate();

    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='answer_attempt' "
            + "and column_name='submission_fingerprint'", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='answer_attempt' "
            + "and column_name='answer_hash'", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select submission_fingerprint from answer_attempt limit 1", String.class))
        .isEqualTo(preserved);

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(25);
  }
}
