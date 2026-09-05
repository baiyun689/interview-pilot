package interview.pilot.interview.application;

import java.util.List;
import java.util.Map;

import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;

/**
 * Stage 3: turns question skeletons plus their question-scoped RAG snapshots into a validated deck
 * with a traceable rubric per question.
 */
public interface RubricGenerator {

  PreparedQuestionDeck generate(
      InterviewBriefSnapshot brief,
      List<QuestionSkeletonOutput.Skeleton> skeletons,
      Map<QuestionCardKey, RagContextSnapshot> snapshots);
}
