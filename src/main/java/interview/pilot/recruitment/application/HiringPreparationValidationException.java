package interview.pilot.recruitment.application;

/** Only application-authored validation messages; safe to show without model output. */
public class HiringPreparationValidationException extends IllegalArgumentException {
  public HiringPreparationValidationException(String message) { super(message); }
}
