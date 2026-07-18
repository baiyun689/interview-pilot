package interview.pilot.async.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import interview.pilot.async.domain.AsyncTaskType;

class AsyncTaskEntityTest {
  @Test
  void pendingRequiresAnOwner() {
    assertThatNullPointerException()
        .isThrownBy(() -> AsyncTaskEntity.pending(
            null, AsyncTaskType.RESUME_ANALYSIS, "resume:1", "{}"))
        .withMessage("userAccountId");
  }

  @Test
  void ownerHasNoPublicMutationPath() {
    assertThat(Arrays.stream(AsyncTaskEntity.class.getMethods())
        .map(java.lang.reflect.Method::getName))
        .doesNotContain("setUserAccountId");
  }
}
