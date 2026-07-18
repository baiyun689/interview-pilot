package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import jakarta.persistence.Column;
import tools.jackson.databind.ObjectMapper;

class InterviewResponseMapperTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final InterviewResponseMapper mapper = new InterviewResponseMapper(
      objectMapper, new StoredAnswerResultCodec(objectMapper));

  @Test
  void rejectsJsonNullPlanWithOneSanitizedStorageError() {
    assertInvalidPlan("null");
  }

  @Test
  void rejectsPlanThatViolatesItsConstructionContractWithOneSanitizedStorageError() {
    assertInvalidPlan("{\"competencies\":[],\"totalTurnBudget\":8}");
  }

  @Test
  void rejectsPlanWhoseBudgetDisagreesWithTheSessionSnapshot() {
    assertInvalidPlan("{\"competencies\":[\"Java\"],\"totalTurnBudget\":9}");
  }

  @Test
  void mapsAValidConsistentPlan() throws Exception {
    InterviewSessionEntity session = session();
    setPlanSnapshot(session, objectMapper.writeValueAsString(
        new InterviewPlan(List.of("Java"), 8)));

    assertThat(mapper.map(session, job(), List.of()).plan())
        .isEqualTo(new InterviewPlan(List.of("Java"), 8));
  }

  @Test
  void providerModelAndPlanColumnsAreCreationOnlyAndHaveNoPublicSetters() throws Exception {
    for (String fieldName : List.of("providerId", "modelName", "planSnapshot")) {
      Column column = InterviewSessionEntity.class.getDeclaredField(fieldName)
          .getAnnotation(Column.class);
      assertThat(column.updatable()).as(fieldName).isFalse();
      String setterName = "set" + Character.toUpperCase(fieldName.charAt(0))
          + fieldName.substring(1);
      assertThat(InterviewSessionEntity.class.getMethods())
          .noneMatch(method -> method.getName().equals(setterName));
    }
  }

  private void assertInvalidPlan(String snapshot) {
    InterviewSessionEntity session = session();
    setPlanSnapshot(session, snapshot);

    assertThatThrownBy(() -> mapper.map(session, job(), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Stored interview plan is invalid")
        .hasNoCause()
        .satisfies(exception -> assertThat(exception.toString()).doesNotContain(snapshot));
  }

  private InterviewSessionEntity session() {
    return InterviewSessionEntity.create(
        1L, 2L, Difficulty.MEDIUM, 8, "deepseek", "deepseek-chat",
        "{\"competencies\":[\"Java\"],\"totalTurnBudget\":8}");
  }

  private JobProfileEntity job() {
    return JobProfileEntity.create("Backend Engineer", "Java", "{}");
  }

  private void setPlanSnapshot(InterviewSessionEntity session, String snapshot) {
    try {
      var field = InterviewSessionEntity.class.getDeclaredField("planSnapshot");
      field.setAccessible(true);
      field.set(session, snapshot);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }
}
