package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class InterviewV21MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v21");

  @Test
  void backfillsDefaultsAndAddsVoiceTablesWithConstraints() {
    Flyway v20 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("20").cleanDisabled(false).load();
    v20.migrate();
    JdbcTemplate jdbc = jdbc();
    insertFixture(jdbc);

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("21").cleanDisabled(false).load();
    latest.migrate();

    assertThat(jdbc.queryForObject(
        "select interview_mode from interview_session where id = 1", String.class))
        .isEqualTo("TEXT");
    assertThat(jdbc.queryForObject(
        "select voice_snapshot from interview_session where id = 1", String.class))
        .isNull();
    assertThat(jdbc.queryForObject(
        "select input_mode from interview_turn where turn_no = 1", String.class))
        .isEqualTo("TEXT");
    assertThat(jdbc.queryForObject(
        "select input_mode from interview_turn where turn_no = 2", String.class))
        .isEqualTo("TEXT");

    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='interview_session' "
            + "and column_name='interview_mode' and is_nullable='NO' "
            + "and column_default='TEXT'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='interview_session' "
            + "and column_name='voice_snapshot' and data_type='json'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema=database() and table_name='interview_turn' "
            + "and column_name='input_mode' and is_nullable='NO' "
            + "and column_default='TEXT'",
        Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update(
        "insert into interview_turn(session_id,turn_no,phase,question_type,source_card_id,"
            + "status,question_text,input_mode,asked_at) "
            + "values(1,3,'FUNDAMENTALS','MAIN',1,'ASKED','Q',null,now(6))"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("input_mode");

    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.tables "
            + "where table_schema=database() "
            + "and table_name in ('voice_recording','question_speech')",
        Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "select count(distinct index_name) from information_schema.statistics "
            + "where table_schema=database() and table_name='voice_recording' "
            + "and index_name in ('uq_voice_recording_id','uq_voice_upload_request',"
            + "'idx_voice_recording_turn','idx_voice_recording_expiry')",
        Integer.class)).isEqualTo(4);
    assertThat(jdbc.queryForObject(
        "select count(distinct index_name) from information_schema.statistics "
            + "where table_schema=database() and table_name='question_speech' "
            + "and index_name in ('uq_question_speech_id','uq_question_speech_turn')",
        Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.table_constraints "
            + "where constraint_schema=database() and table_name='voice_recording' "
            + "and constraint_name in ('fk_voice_recording_user','fk_voice_recording_session',"
            + "'fk_voice_recording_turn')",
        Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject(
        "select count(*) from information_schema.table_constraints "
            + "where constraint_schema=database() and table_name='question_speech' "
            + "and constraint_name in ('fk_question_speech_user','fk_question_speech_session',"
            + "'fk_question_speech_turn')",
        Integer.class)).isEqualTo(3);

    String validRecording = "insert into voice_recording(recording_id,upload_request_id,"
        + "user_account_id,session_id,turn_id,status,expires_at) "
        + "values('%s','%s',1,1,1,'RECEIVING',now(6))";
    jdbc.update(validRecording.formatted(random(), random()));
    assertThatThrownBy(() -> jdbc.update(validRecording.formatted(
        jdbc.queryForObject(
            "select recording_id from voice_recording where id = 1", String.class),
        random())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_voice_recording_id");
    assertThatThrownBy(() -> jdbc.update(validRecording.formatted(random(),
        jdbc.queryForObject(
            "select upload_request_id from voice_recording where id = 1", String.class))))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_voice_upload_request");
    assertThatThrownBy(() -> jdbc.update(validRecording.formatted(random(), random())
        .replace(",1,1,1,'RECEIVING'", ",1,999,1,'RECEIVING'")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("fk_voice_recording_session");

    String validSpeech = "insert into question_speech(speech_id,user_account_id,session_id,"
        + "turn_id,status,text_sha256,provider_id,model_name,voice_name) "
        + "values('%s',1,1,1,'PENDING',repeat('a',64),'dashscope','cosyvoice-v3-flash',"
        + "'longanyang')";
    jdbc.update(validSpeech.formatted(random()));
    assertThatThrownBy(() -> jdbc.update(validSpeech.formatted(
        jdbc.queryForObject(
            "select speech_id from question_speech where id = 1", String.class))))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_question_speech_id");
    assertThatThrownBy(() -> jdbc.update(
        "insert into question_speech(speech_id,user_account_id,session_id,turn_id,status,"
            + "text_sha256,provider_id,model_name,voice_name) "
            + "values('%s',1,1,1,'PENDING',repeat('b',64),'dashscope','m','v')"
            .formatted(random())))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("uq_question_speech_turn");
    assertThatThrownBy(() -> jdbc.update(validSpeech.formatted(random())
        .replace(",1,1,1,'PENDING'", ",999,1,2,'PENDING'")))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("fk_question_speech_user");

    latest.clean();
    latest.migrate();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success=true", Integer.class))
        .isEqualTo(21);
  }

  private JdbcTemplate jdbc() {
    return new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
  }

  private void insertFixture(JdbcTemplate jdbc) {
    jdbc.update("insert into user_account(user_id,email,password_hash,display_name,status) "
        + "values(uuid(),'v21-it@example.com','!','V21','ACTIVE')");
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
        + "values(1,1,'FUNDAMENTALS','MAIN',1,'ASKED','并发问题',now(6))");
    jdbc.update("insert into interview_turn(session_id,turn_no,phase,question_type,"
        + "source_card_id,status,question_text,input_mode,asked_at) "
        + "values(1,2,'FUNDAMENTALS','FOLLOW_UP',1,'ASKED','追问','TEXT',now(6))");
  }

  private String random() {
    return java.util.UUID.randomUUID().toString();
  }
}
