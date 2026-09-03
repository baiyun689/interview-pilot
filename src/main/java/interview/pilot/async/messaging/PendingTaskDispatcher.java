package interview.pilot.async.messaging;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;

@Component
public class PendingTaskDispatcher {
  private static final Logger log = LoggerFactory.getLogger(PendingTaskDispatcher.class);
  private static final int MAX_TASKS_PER_SCAN = 100;

  private final AsyncTaskRepository taskRepository;
  private final TaskMessagePublisher publisher;
  private final AsyncRabbitProperties properties;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public PendingTaskDispatcher(
      AsyncTaskRepository taskRepository,
      TaskMessagePublisher publisher,
      AsyncRabbitProperties properties,
      PlatformTransactionManager transactionManager) {
    this.taskRepository = taskRepository;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = Clock.systemUTC();
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(
      fixedDelayString = "${app.async.rabbit.dispatch-interval:10s}",
      initialDelayString = "${app.async.rabbit.dispatch-initial-delay:10s}")
  public void dispatchPendingTasks() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(properties.getRepublishAfter());
    var tasks = taskRepository.findDispatchable(
        AsyncTaskStatus.PENDING,
        cutoff,
        PageRequest.of(0, MAX_TASKS_PER_SCAN));

    for (AsyncTaskEntity task : tasks) {
      if (!claimForPublishing(task, now, cutoff)) {
        continue;
      }
      try {
        publisher.publish(new TaskMessage(
            task.getTaskId(), task.getTaskType(), task.getBizKey(), task.getExecutionEpoch()));
      } catch (RuntimeException exception) {
        releasePublishingClaim(task, describe(exception));
        log.warn("Could not publish async task {} of type {}",
            task.getTaskId(), task.getTaskType(), exception);
      }
    }
  }

  /**
   * The claim uses its own committed transaction. Publishing inside a transaction leaves a
   * window where a fast consumer can read the old PENDING row and race its status transition.
   */
  private boolean claimForPublishing(AsyncTaskEntity task, Instant now, Instant cutoff) {
    Boolean claimed = transactions.execute(status -> taskRepository.claimForPublishing(
        task.getId(), task.getExecutionEpoch(), now, cutoff, AsyncTaskStatus.PENDING) == 1);
    return Boolean.TRUE.equals(claimed);
  }

  private void releasePublishingClaim(AsyncTaskEntity task, String safeError) {
    transactions.executeWithoutResult(status -> taskRepository.releasePublishingClaim(
        task.getId(), task.getExecutionEpoch(), safeError, AsyncTaskStatus.PENDING));
  }

  private String describe(RuntimeException exception) {
    return "Task message publication temporarily unavailable";
  }
}
