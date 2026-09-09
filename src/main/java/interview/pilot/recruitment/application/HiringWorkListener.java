package interview.pilot.recruitment.application;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import interview.pilot.async.messaging.RabbitTopologyConfig;
import interview.pilot.async.messaging.TaskMessage;

@Component
public class HiringWorkListener {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HiringWorkListener.class);
  private final HiringWorkState state;
  private final java.util.Map<String, HiringWorkProcessor> processors;
  public HiringWorkListener(HiringWorkState state, java.util.List<HiringWorkProcessor> processors) {
    this.state = state;
    this.processors = processors.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(HiringWorkProcessor::kind, p -> p));
  }

  @RabbitListener(queues = RabbitTopologyConfig.HIRING_WORK_QUEUE,
      concurrency = "${app.hiring.worker-concurrency:1}", autoStartup = "${app.hiring.listener-enabled:true}")
  public void receive(TaskMessage message) {
    var claim = state.begin(message);
    if (claim == null) return;
    Object result;
    try {
      var processor = processors.get(claim.kind());
      if (processor == null) throw new IllegalStateException("Unsupported recruitment task kind");
      result = processor.process(claim.inputSnapshot());
    }
    catch (RuntimeException exception) {
      LOG.warn("Hiring work {} kind {} failed: {}", claim.workId(), claim.kind(), exception.getClass().getSimpleName());
      state.finish(claim, null, exception instanceof HiringPreparationValidationException
          ? exception.getMessage() : "任务暂不可用，系统将有限重试；请查看任务状态"); return;
    }
    state.finish(claim, result, null);
  }

  @Scheduled(fixedDelayString = "${app.hiring.recovery-interval:30s}", initialDelayString = "${app.hiring.recovery-interval:30s}")
  public void recover() { state.recoverExpired(); }
}
