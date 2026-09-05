package interview.pilot.async.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class TaskRetryPolicy {
  // The header is the number of retry delays already completed. Failures with
  // values 0, 1, and 2 schedule 5s, 30s, and 120s respectively; a failure
  // after the third completed delay (value 3) is routed to the DLQ.
  static final int MAX_RETRY_COUNT = 3;

  private final TaskMessagePublisher publisher;

  public TaskRetryPolicy(TaskMessagePublisher publisher) {
    this.publisher = publisher;
  }

  public RouteOutcome routeFailure(TaskMessage task, Message source) {
    int completedRetryDelays = retryCountOf(source);
    if (completedRetryDelays < MAX_RETRY_COUNT) {
      publisher.publishRetry(task, completedRetryDelays + 1);
      return RouteOutcome.RETRY;
    }
    publisher.publishDeadLetter(task, completedRetryDelays);
    return RouteOutcome.DEAD_LETTER;
  }

  /** Number of delayed retries already consumed (header x-retry-count); 0 on first delivery. */
  public int retryCountOf(Message source) {
    Object value = source.getMessageProperties()
        .getHeader(RabbitTopologyConfig.RETRY_COUNT_HEADER);
    return value instanceof Number number ? number.intValue() : 0;
  }

  public enum RouteOutcome { RETRY, DEAD_LETTER }
}
