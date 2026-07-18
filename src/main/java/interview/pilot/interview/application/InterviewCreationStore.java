package interview.pilot.interview.application;

import interview.pilot.interview.api.InterviewSessionResponse;

public interface InterviewCreationStore {
  InterviewSessionResponse create(InterviewCreation creation);
}
