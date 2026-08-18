package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.InterviewPlanItem;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.PlanPriority;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.strategy.InterviewProgress;
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

  @Test
  void snapshotRoundTripsProgressSnapshot() {
    UUID session = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    InterviewPlan plan = InterviewPlan.execution(List.of(new InterviewPlanItem(
        "project", "java", "Java", PlanPriority.REQUIRED, 5,
        List.of("并发边界"), List.of(InterviewQuestionMode.PROJECT),
        "JD 必考", false, List.of("边界"), 2, "")), 5, List.of());
    InterviewProgress progress = InterviewProgress.from(plan, List.of());

    AnswerProcessingResult result = new AnswerProcessingResult(
        session, request, 1,
        new AnswerEvaluation(80, "ok", List.of("e"), List.of(),
            new InterviewDecision(NextStep.FINISH, DifficultyAdjustment.KEEP,
                "", "", "done", 0.9)),
        new InterviewDecision(NextStep.FINISH, DifficultyAdjustment.KEEP,
            "", "", "done", 0.9),
        null, Difficulty.MEDIUM, SessionStatus.EVALUATING, false,
        interview.pilot.interview.rag.RagContextSnapshot.notConfigured(),
        null, null, "done", "", progress);

    AnswerProcessingResult read = codec.readCompleted(
        codec.write(result), session, request, 1);

    assertThat(read.progressSnapshot()).isNotNull();
    assertThat(read.progressSnapshot().progressOf("java").missingEvidence())
        .containsExactly("并发边界");
  }

  @Test
  void legacySnapshotWithoutProgressSnapshotStillReads() throws Exception {
    String legacyJson = """
        {"sessionId":"00000000-0000-0000-0000-000000000001",
         "requestId":"00000000-0000-0000-0000-000000000002",
         "turnNo":1,
         "evaluation":{"score":80,"feedback":"ok","evidence":["e"],"missingPoints":[],
           "redFlags":[],"suggestedDecision":{"nextStep":"FINISH",
             "difficultyAdjustment":"KEEP","targetCompetency":"","probeFocus":"",
             "reason":"done","confidence":0.9},
           "referenceFacts":[],"conflictFacts":[],"evidenceAssessments":[]},
         "decision":{"nextStep":"FINISH","difficultyAdjustment":"KEEP",
           "targetCompetency":"","probeFocus":"","reason":"done","confidence":0.9},
         "nextQuestion":null,"nextDifficulty":"MEDIUM","sessionStatus":"EVALUATING",
         "replayed":false,
         "nextRagSnapshot":{"status":"NOT_CONFIGURED","chunks":[],
           "evidenceRefs":[],"groundingMode":"SKILL_GENERAL"},
         "nextDirective":null,"currentDirective":null}
        """;

    AnswerProcessingResult read = new ObjectMapper().readValue(
        legacyJson, AnswerProcessingResult.class);

    assertThat(read.progressSnapshot()).isNull();
    assertThat(read.finishReason()).isEmpty();
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
