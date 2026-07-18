package interview.pilot.async.messaging;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

  public PendingTaskDispatcher(
      AsyncTaskRepository taskRepository,
      TaskMessagePublisher publisher,
      AsyncRabbitProperties properties) {
    this.taskRepository = taskRepository;
    this.publisher = publisher;
    this.properties = properties;
    this.clock = Clock.systemUTC();
  }

  @Scheduled(
      fixedDelayString = "${app.async.rabbit.dispatch-interval:10s}",
      initialDelayString = "${app.async.rabbit.dispatch-initial-delay:10s}")
  @Transactional
  public void dispatchPendingTasks() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(properties.getRepublishAfter());
    var tasks = taskRepository.findDispatchable(
        AsyncTaskStatus.PENDING,
        cutoff,
        PageRequest.of(0, MAX_TASKS_PER_SCAN));

    for (AsyncTaskEntity task : tasks) {
      try {
        publisher.publish(new TaskMessage(
            task.getTaskId(), task.getTaskType(), task.getBizKey(), task.getExecutionEpoch()));
        task.setPublishAttempts(task.getPublishAttempts() + 1);
        task.setLastPublishedAt(now);
        task.setLastError(null);
      } catch (RuntimeException exception) {
        task.setLastError(describe(exception));
        log.warn("Could not publish async task {} of type {}",
            task.getTaskId(), task.getTaskType(), exception);
      }
    }
  }

  private String describe(RuntimeException exception) {
    return "Task message publication temporarily unavailable";
  }
}
