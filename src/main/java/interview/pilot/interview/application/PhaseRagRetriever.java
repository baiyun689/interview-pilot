package interview.pilot.interview.application;

import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.rag.RagContextSnapshot;

public interface PhaseRagRetriever {
  RagContextSnapshot retrieve(InterviewBriefSnapshot brief, InterviewPhase phase);
}
