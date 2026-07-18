package interview.pilot.resume.application;

import interview.pilot.resume.domain.ResumeProfile;

public interface ResumeProfiler {
  ResumeProfile profile(String resumeText);
}
