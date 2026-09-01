package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewBriefSnapshotTest {
  @Test
  void normalizesCustomJdIntoTheSameImmutableBriefShape() {
    var brief = new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "  Java 开发  ", "  熟悉 Spring  ",
        null, null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        "dashscope", "qwen", null, 1);

    assertThat(brief.jobTitle()).isEqualTo("Java 开发");
    assertThat(brief.jobDescription()).isEqualTo("熟悉 Spring");
    assertThat(brief.totalTurns()).isEqualTo(9);
  }

  @Test
  void rejectsBlankCustomJd() {
    assertThatThrownBy(() -> new InterviewBriefSnapshot(
        JobSourceType.CUSTOM, "", "", "Java", " ", null, null,
        Difficulty.MEDIUM, InterviewSize.QUICK, "p", "m", null, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
