package interview.pilot.interview.application;

import interview.pilot.interview.domain.AnswerEvaluation;

public interface AnswerEvaluator {
  AnswerEvaluation evaluate(AnswerEvaluationRequest request);
}
