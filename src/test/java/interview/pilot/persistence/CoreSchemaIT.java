package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class CoreSchemaIT {
  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot");

  private static JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void migrateSchema() {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load()
        .migrate();

    var dataSource = new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Test
  void createsTheFixedInterviewBusinessTablesAndRemovesLegacyJobProfile() {
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.tables "
            + "where table_schema = database() and table_name in "
            + "('resume','interview_session','interview_question_card','interview_turn',"
            + "'answer_attempt','interview_report','async_task','ai_setting')",
        Integer.class)).isEqualTo(8);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.tables where table_schema = database() "
            + "and table_name = 'job_profile'", Integer.class)).isZero();
  }

  @Test
  void createsOptimisticLockVersionForInterviewSession() {
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema = database() and table_name = 'interview_session' "
            + "and column_name = 'version' and data_type = 'bigint' and is_nullable = 'NO' "
            + "and column_default = '0'",
        Integer.class)).isEqualTo(1);
  }

  @Test
  void requiresImmutableBriefAndQuestionCardSnapshots() {
    List<String> requiredSessionColumns = jdbcTemplate.queryForList(
        "select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'interview_session' "
            + "and column_name in ('interview_size','job_source_type','job_title',"
            + "'total_turn_budget','provider_id','model_name','brief_snapshot') "
            + "and is_nullable = 'NO' order by ordinal_position",
        String.class);
    List<String> requiredTurnColumns = jdbcTemplate.queryForList(
        "select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'interview_question_card' "
            + "and column_name in ('phase','phase_sequence','rag_context_snapshot',"
            + "'follow_up_quota') and is_nullable = 'NO' order by ordinal_position",
        String.class);

    assertThat(requiredSessionColumns).containsExactly(
        "interview_size", "job_source_type", "job_title", "total_turn_budget",
        "provider_id", "model_name", "brief_snapshot");
    assertThat(requiredTurnColumns).containsExactly(
        "phase", "phase_sequence", "rag_context_snapshot", "follow_up_quota");

    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.columns where table_schema = database() "
            + "and table_name = 'interview_turn' and column_name in "
            + "('target_competency','score','feedback','decision','next_difficulty')",
        Integer.class)).isZero();
  }

  @Test
  void separatesPublishAttemptsAndTheLastPublishErrorFromBusinessAttempts() {
    List<String> columns = jdbcTemplate.queryForList(
        "select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'async_task' "
            + "and column_name in ('attempt_count', 'publish_attempts', 'last_error') "
            + "order by ordinal_position",
        String.class);

    assertThat(columns).containsExactly("attempt_count", "publish_attempts", "last_error");
  }

  @Test
  void enforcesTurnNumberAndRequestIdIdempotency() {
    List<String> uniqueIndexes = jdbcTemplate.queryForList(
        "select group_concat(column_name order by seq_in_index) "
            + "from information_schema.statistics "
            + "where table_schema = database() and table_name = 'interview_turn' "
            + "and non_unique = 0 group by index_name",
        String.class);

    assertThat(uniqueIndexes).contains("session_id,turn_no", "request_id");
  }

  @Test
  void permitsOnlyOneAiSettingRow() {
    assertThatThrownBy(() -> jdbcTemplate.update(
        "insert into ai_setting (setting_key, provider_id) values (?, ?)",
        "secondary", "deepseek"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aiSettingStoresOnlyThePreconfiguredProviderSelection() {
    List<String> columns = jdbcTemplate.queryForList(
        "select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'ai_setting' "
            + "order by ordinal_position",
        String.class);

    assertThat(columns).containsExactly(
        "id", "singleton_guard", "setting_key", "provider_id",
        "created_at", "updated_at", "version");
  }

  @Test
  void insertsTheDefaultProviderSingleton() {
    Map<String, Object> setting = jdbcTemplate.queryForMap(
        "select id, setting_key, provider_id from ai_setting where id = 1");

    assertThat(setting.get("id")).isEqualTo(1L);
    assertThat(setting.get("setting_key")).isEqualTo("default-provider");
    assertThat(setting.get("provider_id")).isEqualTo("dashscope");
  }
}
