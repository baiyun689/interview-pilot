package interview.pilot.interview.application;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Component;

import interview.pilot.interview.domain.InterviewPhase;

@Component
public class RandomFollowUpQuotaAllocator implements FollowUpQuotaAllocator {
  @Override
  public int allocate(InterviewPhase phase) {
    return phase != null && phase.allowsFollowUp()
        ? ThreadLocalRandom.current().nextInt(1, 3)
        : 0;
  }
}
