package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.SessionStatus;
import tools.jackson.databind.ObjectMapper;

class StoredAnswerResultCodecTest {
  private final StoredAnswerResultCodec codec = new StoredAnswerResultCodec(new ObjectMapper());

  @Test
  void rejectsWrongSessionRequestTurnAndMalformedSnapshotsWithOneSanitizedError() {
    UUID session = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    String snapshot = codec.write(result(session, request));

    assertInvalid(() -> codec.readCompleted(snapshot, UUID.randomUUID(), request, 1));
    assertInvalid(() -> codec.readCompleted(snapshot, session, UUID.randomUUID(), 1));
    assertInvalid(() -> codec.readCompleted(snapshot, session, request, 2));
    assertInvalid(() -> codec.readCompleted("{secret-broken", session, request, 1));
  }

  private AnswerProcessingResult result(UUID session, UUID request) {
    var decision = new InterviewDecision(
        NextStep.FINISH, DifficultyAdjustment.KEEP, "", "", "done", 0.9);
    return new AnswerProcessingResult(
        session, request, 1,
        new AnswerEvaluation(80, "ok", List.of("e"), List.of(), decision),
        decision, null, Difficulty.MEDIUM, SessionStatus.EVALUATING, false);
  }

  private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Stored answer result is invalid")
        .hasNoCause();
  }
}
