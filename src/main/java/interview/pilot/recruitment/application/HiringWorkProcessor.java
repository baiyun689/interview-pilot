package interview.pilot.recruitment.application;

/** Performs one external operation using its immutable input, outside database transactions. */
public interface HiringWorkProcessor {
  String kind();
  Object process(String inputSnapshot);
}
