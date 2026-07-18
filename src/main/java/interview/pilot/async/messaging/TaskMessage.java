package interview.pilot.async.messaging;

import java.util.UUID;

import interview.pilot.async.domain.AsyncTaskType;

public record TaskMessage(
    UUID taskId, AsyncTaskType taskType, String bizKey, int executionEpoch) {
  public TaskMessage(UUID taskId, AsyncTaskType taskType, String bizKey) {
    this(taskId, taskType, bizKey, 0);
  }

  public TaskMessage {
    if (executionEpoch < 0) {
      throw new IllegalArgumentException("executionEpoch must not be negative");
    }
  }
}
