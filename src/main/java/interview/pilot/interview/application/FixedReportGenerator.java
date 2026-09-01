package interview.pilot.interview.application;

import interview.pilot.interview.domain.FixedInterviewReport;

public interface FixedReportGenerator {
  FixedInterviewReport generate(String providerId, String modelName, FixedReportInput input);
}
