package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InterviewV5MigrationIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot_v5_migration");

  @Test
  void askedTurnsHaveNoAnswerIdempotencyKeyAndTheUniqueKeyStillRejectsDuplicates() {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("4")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    String processingRequestId = insertV4Turns(jdbc);

    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("5")
        .load()
        .migrate();

    Integer nullable = jdbc.queryForObject(
        "select count(*) from information_schema.columns where table_schema=database() "
            + "and table_name='interview_turn' and column_name='request_id' and is_nullable='YES'",
        Integer.class);

    assertThat(nullable).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select data_type from information_schema.columns where table_schema=database() "
            + "and table_name='interview_turn' and column_name='request_id'",
        String.class)).isEqualTo("char");
    assertThat(jdbc.queryForObject(
        "select request_id from interview_turn where turn_no=1", String.class)).isNull();
    assertThat(jdbc.queryForObject(
        "select request_id from interview_turn where turn_no=2", String.class))
        .isEqualTo(processingRequestId);
    assertThatThrownBy(() -> jdbc.update("insert into interview_turn "
        + "(session_id,turn_no,request_id,status,difficulty,question_text,target_competency,"
        + "answer_text,asked_at) values(1,3,?,'PROCESSING','MEDIUM','Q','Java','a',now(6))",
        processingRequestId)).isInstanceOf(DuplicateKeyException.class);
  }

  private String insertV4Turns(JdbcTemplate jdbc) {
    jdbc.update("insert into resume "
        + "(resume_id,original_filename,content_hash,parsed_text,skills_snapshot,status) "
        + "values (uuid(),'candidate.txt',repeat('a',64),'Java',json_object(),'READY')");
    jdbc.update("insert into job_profile "
        + "(job_id,title,description_text,requirements_snapshot) "
        + "values (uuid(),'Backend','Java',json_object())");
    jdbc.update("insert into interview_session "
        + "(session_id,resume_id,job_profile_id,status,difficulty,current_turn_no,total_turn_budget,"
        + "provider_id,model_name,plan_snapshot) "
        + "values (uuid(),1,1,'INTERVIEWING','MEDIUM',1,5,'deepseek','deepseek-chat',"
        + "json_object('competencies',json_array('Java'),'totalTurnBudget',5))");
    jdbc.update("insert into interview_turn "
        + "(session_id,turn_no,request_id,status,difficulty,question_text,target_competency,asked_at) "
        + "values (1,1,?,'ASKED','MEDIUM','Explain Java','Java',now(6))",
        UUID.randomUUID().toString());
    String processingRequestId = UUID.randomUUID().toString();
    jdbc.update("insert into interview_turn "
        + "(session_id,turn_no,request_id,status,difficulty,question_text,target_competency,"
        + "answer_text,asked_at) "
        + "values (1,2,?,'PROCESSING','MEDIUM','Explain Spring','Spring','answer',now(6))",
        processingRequestId);
    return processingRequestId;
  }
}
