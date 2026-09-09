package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InterviewV25MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v25");

  @Test
  void addsAnswerEvaluationAndBackfillsHistoricalTurnsToNotRequired() {
    Flyway v24 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("24").cleanDisabled(false).load();
    v24.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    jdbc.update("insert into user_account(user_id,email,password_hash,display_name,status) "
        + "values(uuid(),'v25-it@example.com','!','V25','ACTIVE')");
    jdbc.update("insert into interview_session(user_account_id,session_id,status,difficulty,"
        + "interview_size,job_source_type,job_title,current_turn_no,current_main_question_no,"
        + "total_main_question_count,provider_id,model_name,brief_snapshot) "
        + "values(1,uuid(),'EVALUATING','MEDIUM','STANDARD','CUSTOM','Java 后端',1,1,9,"
        + "'dashscope','qwen',json_object())");
    jdbc.update("insert into interview_question_card(session_id,phase,phase_sequence,topic,"
        + "question_text,focus_points,grounding_mode,rag_status,rag_context_snapshot,source_ids,"
        + "follow_up_quota,fallback_follow_up) "
        + "values(1,'FUNDAMENTALS',1,'并发','请解释 MVCC','[]','GENERAL','NOT_REQUESTED',"
        + "'{}','[]',1,'ReadView 如何判断可见性？')");
    jdbc.update("insert into interview_turn(session_id,turn_no,phase,question_type,source_card_id,"
        + "status,question_text,answer_text,input_mode,asked_at) "
        + "values(1,1,'FUNDAMENTALS','MAIN',1,'COMPLETED','请解释 MVCC','通过 undo log 实现',"
        + "'KEYBOARD',CURRENT_TIMESTAMP(6))");

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .cleanDisabled(false).load();
    latest.migrate();

    for (String column : List.of("answer_evaluation", "eval_status")) {
      assertThat(columnCount(jdbc, column)).isEqualTo(1);
    }
    // Historical turns are backfilled to NOT_REQUIRED with a NULL evaluation payload.
    assertThat(jdbc.queryForObject(
        "select eval_status from interview_turn limit 1", String.class)).isEqualTo("NOT_REQUIRED");
    assertThat(jdbc.queryForObject(
        "select answer_evaluation from interview_turn limit 1", String.class)).isNull();

    // The new JSON column accepts a structured evaluation snapshot.
    jdbc.update("update interview_turn set eval_status='OK', answer_evaluation=json_object("
        + "'score',80,'groundingMode','KNOWLEDGE_ASSISTED','status','OK')");
    assertThat(jdbc.queryForObject(
        "select json_extract(answer_evaluation,'$.score') from interview_turn limit 1",
        Integer.class)).isEqualTo(80);

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(33);
  }

  private int columnCount(JdbcTemplate jdbc, String column) {
    return jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='interview_turn' "
            + "and column_name=?",
        Integer.class, column);
  }
}
