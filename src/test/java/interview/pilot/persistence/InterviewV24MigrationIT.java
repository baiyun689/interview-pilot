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
class InterviewV24MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v24");

  @Test
  void addsQuestionScopedColumnsWhileKeepingHistoricalCardsNullable() {
    Flyway v23 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("23").cleanDisabled(false).load();
    v23.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    jdbc.update("insert into user_account(user_id,email,password_hash,display_name,status) "
        + "values(uuid(),'v24-it@example.com','!','V24','ACTIVE')");
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

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .cleanDisabled(false).load();
    latest.migrate();

    for (String column : List.of("knowledge_point", "retrieval_keywords", "rubric")) {
      assertThat(columnCount(jdbc, column)).isEqualTo(1);
    }
    // Historical cards keep the new columns NULL (no backfill required).
    assertThat(jdbc.queryForObject(
        "select knowledge_point from interview_question_card limit 1", String.class)).isNull();
    assertThat(jdbc.queryForObject(
        "select rubric from interview_question_card limit 1", String.class)).isNull();

    // New columns accept the question-scoped payload.
    jdbc.update("update interview_question_card set knowledge_point='mysql.mvcc', "
        + "retrieval_keywords=json_array('MVCC','ReadView'), rubric=json_array()");
    assertThat(jdbc.queryForObject(
        "select knowledge_point from interview_question_card limit 1", String.class))
        .isEqualTo("mysql.mvcc");

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(24);
  }

  private int columnCount(JdbcTemplate jdbc, String column) {
    return jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='interview_question_card' "
            + "and column_name=?",
        Integer.class, column);
  }
}
