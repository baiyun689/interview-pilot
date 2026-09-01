package interview.pilot.interview.application;

import interview.pilot.interview.domain.InterviewPhase;

public interface FollowUpQuotaAllocator {
  int allocate(InterviewPhase phase);
}
