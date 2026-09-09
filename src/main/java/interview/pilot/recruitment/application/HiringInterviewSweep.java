package interview.pilot.recruitment.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HiringInterviewSweep {
  private final HiringInterviewLifecycle lifecycle;
  public HiringInterviewSweep(HiringInterviewLifecycle lifecycle) {this.lifecycle=lifecycle;}
  @Scheduled(fixedDelay=5000, initialDelay=15000)
  public void run() {
    for (Long id : lifecycle.candidates()) lifecycle.reconcile(id);
  }
}
