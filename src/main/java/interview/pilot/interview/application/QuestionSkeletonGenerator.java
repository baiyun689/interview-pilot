package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.InterviewBriefSnapshot;

/** Stage 1: generates question skeletons (text + knowledge point + focus points), without RAG. */
public interface QuestionSkeletonGenerator {

  List<QuestionSkeletonOutput.Skeleton> generate(InterviewBriefSnapshot brief);
}
