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
class UserOwnershipV11MigrationIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v11");

  @Test
  void v11BackfillsLegacyOwnerAndScopesResumeHash() {
    Flyway v10 = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("10").load();
    v10.migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    jdbc.update("insert into resume(resume_id,original_filename,content_hash,status) "
        + "values(uuid(),'legacy.pdf',repeat('a',64),'PENDING')");
    jdbc.update("insert into job_profile(job_id,title,description_text,requirements_snapshot,skill_snapshot) "
        + "values(uuid(),'Legacy Job','Legacy description',json_object(),json_object())");
    jdbc.update("insert into interview_session(session_id,resume_id,job_profile_id,status,difficulty,"
        + "total_turn_budget,provider_id,model_name,plan_snapshot) "
        + "values(uuid(),1,1,'INTERVIEWING','MEDIUM',1,'legacy','legacy-model',json_object())");
    jdbc.update("insert into async_task(task_id,task_type,biz_key,status,payload_snapshot) "
        + "values(uuid(),'RESUME_ANALYSIS','legacy:resume:1','PENDING',json_object())");

    Flyway latest = Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target("12").load();
    latest.migrate();

    Integer users = jdbc.queryForObject(
        "select count(*) from user_account where email = 'legacy-demo@invalid.local'",
        Integer.class);
    assertThat(users).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "select status from user_account where email = 'legacy-demo@invalid.local'", String.class))
        .isEqualTo("DISABLED");
    assertThat(ownerCount(jdbc, "resume")).isZero();
    assertThat(ownerCount(jdbc, "job_profile")).isZero();
    assertThat(ownerCount(jdbc, "interview_session")).isZero();
    assertThat(ownerCount(jdbc, "async_task")).isZero();
    assertThat(columnNullable(jdbc, "resume")).isEqualTo("NO");
    assertThat(columnNullable(jdbc, "job_profile")).isEqualTo("NO");
    assertThat(columnNullable(jdbc, "interview_session")).isEqualTo("NO");
    assertThat(columnNullable(jdbc, "async_task")).isEqualTo("NO");
    assertThat(columnDefault(jdbc, "resume")).isNull();
    assertThat(indexColumns(jdbc, "resume", "uq_resume_user_hash"))
        .containsExactly("user_account_id", "content_hash");

    assertThatThrownBy(() -> jdbc.update(
        "insert into resume(user_account_id,resume_id,original_filename,content_hash,status) "
            + "values(999999,uuid(),'invalid-owner.pdf',repeat('b',64),'PENDING')"))
        .hasMessageContaining("fk_resume_user");

    jdbc.update("insert into user_account(user_id,email,password_hash,display_name,status) "
        + "values(uuid(),'other-user@invalid.local','!','Other User','ACTIVE')");
    jdbc.update("insert into resume(user_account_id,resume_id,original_filename,content_hash,status) "
        + "values(1,uuid(),'legacy-same-hash.pdf',repeat('c',64),'PENDING')");
    jdbc.update("insert into resume(user_account_id,resume_id,original_filename,content_hash,status) "
        + "values(2,uuid(),'other-same-hash.pdf',repeat('c',64),'PENDING')");
    assertThatThrownBy(() -> jdbc.update(
        "insert into resume(user_account_id,resume_id,original_filename,content_hash,status) "
            + "values(1,uuid(),'duplicate-same-hash.pdf',repeat('c',64),'PENDING')"))
        .hasMessageContaining("uq_resume_user_hash");
  }

  private int ownerCount(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject("select count(*) from " + table + " where user_account_id is null",
        Integer.class);
  }

  private String columnNullable(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "select is_nullable from information_schema.columns where table_schema = database() "
            + "and table_name = ? and column_name = 'user_account_id'", String.class, table);
  }

  private String columnDefault(JdbcTemplate jdbc, String table) {
    return jdbc.queryForObject(
        "select column_default from information_schema.columns where table_schema = database() "
            + "and table_name = ? and column_name = 'user_account_id'", String.class, table);
  }

  private java.util.List<String> indexColumns(JdbcTemplate jdbc, String table, String index) {
    return jdbc.queryForList(
        "select column_name from information_schema.statistics where table_schema = database() "
            + "and table_name = ? and index_name = ? order by seq_in_index",
        String.class, table, index);
  }
}
