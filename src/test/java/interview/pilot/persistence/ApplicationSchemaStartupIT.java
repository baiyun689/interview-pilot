package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.redisson.api.RedissonClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties =
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4")
@Testcontainers
class ApplicationSchemaStartupIT {
  @MockitoBean
  private RedissonClient redissonClient;

  @Container
  private static final MySQLContainer MYSQL =
      new MySQLContainer(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("interview_pilot");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @Autowired
  private ConfigurableApplicationContext applicationContext;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void startsAfterAutomaticallyApplyingFlywayMigration() {
    assertThat(applicationContext.isActive()).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.tables "
            + "where table_schema = database() and table_name in "
            + "('resume','interview_session','interview_question_card','interview_turn',"
            + "'answer_attempt','interview_report','async_task','ai_setting')",
        Integer.class)).isEqualTo(8);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.tables "
            + "where table_schema = database() and table_name = 'job_profile'",
        Integer.class)).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.tables "
            + "where table_schema = database() and table_name = 'flyway_schema_history'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from information_schema.columns "
            + "where table_schema = database() and table_name = 'async_task' "
            + "and column_name in ('publish_attempts', 'last_error')",
        Integer.class)).isEqualTo(2);
  }
}
