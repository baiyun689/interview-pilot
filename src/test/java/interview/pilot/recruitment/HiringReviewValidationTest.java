package interview.pilot.recruitment;

import static org.assertj.core.api.Assertions.*;
import interview.pilot.recruitment.application.HiringReviewService.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HiringReviewValidationTest {
  @Test void incompleteDraftIsValidButNullDimensionsAreRejected() {
    try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var validator=factory.getValidator();
      var draft=new Save(-1,new Content(List.of(new Dimension("技术","")),List.of(),"","MORE_ASSESSMENT"),false);
      assertThat(validator.validate(draft)).isEmpty();
      var malformed=new Save(-1,new Content(Arrays.asList((Dimension)null),List.of(),"","MORE_ASSESSMENT"),false);
      assertThat(validator.validate(malformed)).isNotEmpty();
    }
  }
}
