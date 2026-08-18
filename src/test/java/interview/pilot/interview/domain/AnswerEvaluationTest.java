package interview.pilot.interview.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AnswerEvaluationTest {

  @Test
  void evidenceAssessmentsDefaultsToEmpty() {
    AnswerEvaluation evaluation = new AnswerEvaluation(
        80, "反馈", List.of("项目证据"), List.of(), List.of(),
        new InterviewDecision(NextStep.FOLLOW_UP, DifficultyAdjustment.KEEP,
            "Java", "", "继续", 0.9),
        List.of(), List.of());

    assertThat(evaluation.evidenceAssessments()).isEmpty();
  }

  @Test
  void evidenceAssessmentNormalizesBlankClaimToNotObserved() {
    AnswerEvaluation.EvidenceAssessment assessment =
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", true, "   ");

    assertThat(assessment.observed()).isFalse();
    assertThat(assessment.claim()).isEmpty();
  }

  @Test
  void evidenceAssessmentNormalizesClaimWhenNotObserved() {
    AnswerEvaluation.EvidenceAssessment assessment =
        new AnswerEvaluation.EvidenceAssessment("chunking_rationale", false, "   ");

    assertThat(assessment.observed()).isFalse();
    assertThat(assessment.claim()).isEmpty();
  }

  @Test
  void legacySnapshotWithoutEvidenceAssessmentsStillDeserializes() throws Exception {
    String legacyJson = """
        {"score":70,"feedback":"反馈","evidence":["项目证据"],"missingPoints":[],"redFlags":[],
         "suggestedDecision":{"nextStep":"FOLLOW_UP","difficultyAdjustment":"KEEP",
           "targetCompetency":"Java","probeFocus":"","reason":"继续","confidence":0.9},
         "referenceFacts":[],"conflictFacts":[]}
        """;

    AnswerEvaluation evaluation = new ObjectMapper().readValue(legacyJson, AnswerEvaluation.class);

    assertThat(evaluation.score()).isEqualTo(70);
    assertThat(evaluation.evidenceAssessments()).isEmpty();
  }
}
