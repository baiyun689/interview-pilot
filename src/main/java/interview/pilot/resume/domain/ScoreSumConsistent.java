package interview.pilot.resume.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** 总分必须等于五个分项评分之和;null 字段由字段级注解负责。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ScoreSumConsistentValidator.class)
public @interface ScoreSumConsistent {
  String message() default "Overall score must equal the sum of dimension scores";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
