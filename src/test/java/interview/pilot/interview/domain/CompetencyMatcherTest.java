package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompetencyMatcherTest {
  @Test
  void matchesWholeTechnologyTokensWithoutConfusingJavaAndJavaScript() {
    assertThat(CompetencyMatcher.related("Java", "Java 基础与并发")).isTrue();
    assertThat(CompetencyMatcher.related("Java", "JavaScript 工程化")).isFalse();
  }
}
