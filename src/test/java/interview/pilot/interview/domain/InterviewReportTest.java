package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InterviewReportTest {
  @Test
  void rejectsOutOfRangeScoresAndEmptyNarrativeSections() {
    assertThatThrownBy(() -> new InterviewReport(
        101, Map.of("Java", 90), List.of("Strong"), List.of("Depth"), "Summary"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InterviewReport(
        90, Map.of("Java", -1), List.of("Strong"), List.of("Depth"), "Summary"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InterviewReport(
        90, Map.of(), List.of("Strong"), List.of("Depth"), "Summary"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InterviewReport(
        90, Map.of("Java", 90), List.of(), List.of("Depth"), "Summary"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InterviewReport(
        90, Map.of("Java", 90), List.of("Strong"), List.of("Depth"), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
