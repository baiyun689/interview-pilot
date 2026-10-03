package interview.pilot.async.idempotency;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MySqlProcessingClaimIT {
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
  static JdbcTemplate jdbc;
  static ProcessingClaim claim;
  @BeforeAll static void setup() throws Exception {
    var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(ds);
    jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V37__background_run_lease.sql")).split(";")[0]);
    claim = new MySqlProcessingClaim(jdbc, new DataSourceTransactionManager(ds));
  }
  @Test void concurrentAcquisitionHasOneOwner() throws Exception {
    try (var pool = Executors.newFixedThreadPool(12)) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<Optional<String>>>();
      for (int i=0; i<12; i++) futures.add(pool.submit(() -> { start.await(); return claim.acquire("concurrent", Duration.ofMinutes(1)); }));
      start.countDown();
      int owners=0;
      for (var future:futures) if(future.get(10, TimeUnit.SECONDS).isPresent()) owners++;
      assertThat(owners).isEqualTo(1);
    }
  }
  @Test void expiredOwnerCannotReleaseOrCompleteSuccessor() {
    String old=claim.acquire("takeover", Duration.ofMinutes(1)).orElseThrow();
    jdbc.update("update background_run_lease set lease_until=current_timestamp(6)-interval 1 second where business_key='takeover'");
    String current=claim.acquire("takeover", Duration.ofMinutes(1)).orElseThrow();
    assertThat(current).isNotEqualTo(old);
    assertThat(claim.release("takeover",old)).isFalse();
    assertThat(claim.complete("takeover",old,Duration.ofMinutes(1))).isFalse();
    assertThat(claim.acquire("takeover",Duration.ofMinutes(1))).isEmpty();
    assertThat(claim.release("takeover",current)).isTrue();
    assertThat(claim.acquire("takeover",Duration.ofMinutes(1))).isPresent();
  }
  @Test void terminalClearNeverRemovesActiveOwner() {
    String token=claim.acquire("terminal",Duration.ofMinutes(1)).orElseThrow();
    assertThat(claim.clearTerminal("terminal")).isEqualTo(ProcessingClaim.ClearResult.ACTIVE);
    assertThat(claim.complete("terminal",token,Duration.ofMinutes(1))).isTrue();
    assertThat(claim.acquire("terminal",Duration.ofMinutes(1))).isEmpty();
    assertThat(claim.clearTerminal("terminal")).isEqualTo(ProcessingClaim.ClearResult.CLEARED);
    assertThat(claim.clearTerminal("terminal")).isEqualTo(ProcessingClaim.ClearResult.ABSENT);
  }
}
