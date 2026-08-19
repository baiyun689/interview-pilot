package interview.pilot.resume.application;

import interview.pilot.resume.domain.ResumeAnalysisResult;

public interface ResumeProfiler {
  ResumeAnalysisResult analyze(String resumeText);
}
