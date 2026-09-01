package interview.pilot.interview.application;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.rag.RagContextSnapshot;

public interface FollowUpGenerator {
  String generate(
      String providerId,
      String modelName,
      String parentQuestion,
      String latestAnswer,
      Object focusPoints,
      RagContextSnapshot rag,
      InterviewPhase phase,
      Difficulty difficulty);
}
