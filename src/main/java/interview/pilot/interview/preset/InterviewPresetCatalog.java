package interview.pilot.interview.preset;

import java.util.List;

public interface InterviewPresetCatalog {
  List<InterviewPreset> list();

  InterviewPreset require(String id);
}
