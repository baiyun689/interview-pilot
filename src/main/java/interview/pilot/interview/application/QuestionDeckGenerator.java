package interview.pilot.interview.application;

import java.util.Map;

import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;

public interface QuestionDeckGenerator {
  PreparedQuestionDeck generate(
      InterviewBriefSnapshot brief,
      Map<InterviewPhase, RagContextSnapshot> ragByPhase);
}
