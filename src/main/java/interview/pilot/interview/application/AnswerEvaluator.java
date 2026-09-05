package interview.pilot.interview.application;

import interview.pilot.interview.domain.AnswerEvaluation;

/** Judges one answered turn against its frozen rubric (and reference when available). */
public interface AnswerEvaluator {
  AnswerEvaluation evaluate(AnswerEvaluationInput input);
}
