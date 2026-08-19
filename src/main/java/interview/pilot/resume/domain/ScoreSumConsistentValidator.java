package interview.pilot.resume.domain;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ScoreSumConsistentValidator
    implements ConstraintValidator<ScoreSumConsistent, ResumeEvaluation> {

  @Override
  public boolean isValid(ResumeEvaluation evaluation, ConstraintValidatorContext context) {
    if (evaluation == null) {
      return true;
    }
    var overall = evaluation.overallScore();
    var detail = evaluation.scoreDetail();
    if (overall == null || detail == null) {
      return true;
    }
    if (detail.projectScore() == null
        || detail.skillMatchScore() == null
        || detail.contentScore() == null
        || detail.structureScore() == null
        || detail.expressionScore() == null) {
      return true;
    }
    int sum = detail.projectScore()
        + detail.skillMatchScore()
        + detail.contentScore()
        + detail.structureScore()
        + detail.expressionScore();
    return overall.equals(sum);
  }
}
