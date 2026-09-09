package interview.pilot.recruitment;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import interview.pilot.auth.application.CurrentUserProvider;
import interview.pilot.recruitment.api.HiringReviewController;
import interview.pilot.recruitment.application.HiringReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HiringReviewControllerTest {
  @Test void malformedDimensionsAreRejectedBeforePersistence() throws Exception {
    var service=mock(HiringReviewService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new HiringReviewController(service,mock(CurrentUserProvider.class))).build();
    mvc.perform(put("/api/organizations/1/reviews/invitation").contentType(MediaType.APPLICATION_JSON).content("""
        {"version":-1,"submit":false,"content":{"dimensions":[null],"evidenceTurns":[],"notes":"","decision":"NEXT_ROUND"}}
        """)).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
