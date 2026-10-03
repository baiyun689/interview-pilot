package interview.pilot.async.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short scheduler coordination only. Async consumers use their own async_task execution lease. */
@Component
public class MySqlProcessingClaim implements ProcessingClaim {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public MySqlProcessingClaim(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    transactions = new TransactionTemplate(manager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Override public Optional<String> acquire(String businessKey, Duration ttl) {
    long micros = micros(ttl);
    String token = UUID.randomUUID().toString();
    return transactions.execute(status -> {
      jdbc.update("insert into background_run_lease (business_key, token, completed, lease_until) "
          + "values (?, ?, false, timestampadd(microsecond, ?, current_timestamp(6))) "
          + "on duplicate key update business_key=background_run_lease.business_key", businessKey, token, micros);
      jdbc.update("update background_run_lease set token=?, completed=false, "
          + "lease_until=timestampadd(microsecond, ?, current_timestamp(6)) "
          + "where business_key=? and lease_until <= current_timestamp(6)", token, micros, businessKey);
      return Boolean.TRUE.equals(jdbc.queryForObject(
          "select token=? from background_run_lease where business_key=?", Boolean.class, token, businessKey))
          ? Optional.of(token) : Optional.empty();
    });
  }

  @Override public boolean complete(String businessKey, String token, Duration ttl) {
    return jdbc.update("update background_run_lease set completed=true, "
        + "lease_until=timestampadd(microsecond, ?, current_timestamp(6)) "
        + "where business_key=? and token=? and completed=false and lease_until > current_timestamp(6)",
        micros(ttl), businessKey, token) == 1;
  }

  @Override public boolean release(String businessKey, String token) {
    return jdbc.update("delete from background_run_lease where business_key=? and token=? and completed=false",
        businessKey, token) == 1;
  }

  @Override public ClearResult clearTerminal(String businessKey) {
    return transactions.execute(status -> {
      var states = jdbc.query("select completed, lease_until <= current_timestamp(6) as expired "
          + "from background_run_lease where business_key=? for update",
          (row, n) -> new boolean[]{row.getBoolean("completed"), row.getBoolean("expired")}, businessKey);
      if (states.isEmpty()) return ClearResult.ABSENT;
      if (!states.getFirst()[0] && !states.getFirst()[1]) return ClearResult.ACTIVE;
      jdbc.update("delete from background_run_lease where business_key=?", businessKey);
      return states.getFirst()[1] ? ClearResult.ABSENT : ClearResult.CLEARED;
    });
  }

  private static long micros(Duration ttl) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("Claim duration must be positive and at most one day");
    return Math.max(1, ttl.toNanos() / 1_000);
  }
}
