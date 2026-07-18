package interview.pilot.interview.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import jakarta.persistence.Column;

class AnswerAttemptEntityTest {
  @Test
  void identityColumnsAreCreationOnlyAndHaveNoPublicSetters() throws Exception {
    for (String fieldName : List.of("requestId", "sessionId", "turnId", "answerHash")) {
      Column column = AnswerAttemptEntity.class.getDeclaredField(fieldName).getAnnotation(Column.class);
      assertThat(column.updatable()).as(fieldName).isFalse();
      String setter = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
      assertThat(AnswerAttemptEntity.class.getMethods())
          .noneMatch(method -> method.getName().equals(setter));
    }
  }

  @Test
  void exposesGuardedTerminalTransitionsInsteadOfGenericStateSetters() {
    assertThat(AnswerAttemptEntity.class.getMethods())
        .noneMatch(method -> method.getName().equals("setStatus")
            || method.getName().equals("setResultSnapshot")
            || method.getName().equals("setSafeError"));
  }
}
