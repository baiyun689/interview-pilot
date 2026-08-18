package interview.pilot.interview.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.skill.InterviewQuestionMode;
import tools.jackson.databind.ObjectMapper;

class TurnDirectiveTest {

  @Test
  void askDirectiveRequiresCompetencyAndEvidenceTargets() {
    assertThatThrownBy(() -> new TurnDirective(
        TurnAction.ASK, "depth", " ", Difficulty.MEDIUM, List.of("并发边界"),
        InterviewQuestionMode.PROJECT, false, "", "PLAN_FIRST_TURN", "", null, List.of(),
        "", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TurnDirective(
        TurnAction.ASK, "depth", "Java", Difficulty.MEDIUM, List.of(),
        InterviewQuestionMode.PROJECT, false, "", "PLAN_FIRST_TURN", "", null, List.of(),
        "", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void finishDirectiveRequiresFinishReasonAndNormalizesAskFields() {
    TurnDirective directive = new TurnDirective(
        TurnAction.FINISH, "depth", "Java", Difficulty.MEDIUM, List.of("并发边界"),
        InterviewQuestionMode.PROJECT, true, "焦点", "", "", null, List.of(),
        "TURN_BUDGET_EXHAUSTED", "Java（缺：并发边界）");

    assertThat(directive.action()).isEqualTo(TurnAction.FINISH);
    assertThat(directive.stageId()).isEmpty();
    assertThat(directive.competency()).isEmpty();
    assertThat(directive.evidenceTargets()).isEmpty();
    assertThat(directive.finishReason()).isEqualTo("TURN_BUDGET_EXHAUSTED");
    assertThat(directive.unfinishedEvidence()).isEqualTo("Java（缺：并发边界）");
  }

  @Test
  void finishDirectiveWithoutFinishReasonIsRejected() {
    assertThatThrownBy(() -> new TurnDirective(
        TurnAction.FINISH, "depth", "Java", Difficulty.MEDIUM, List.of("并发边界"),
        InterviewQuestionMode.PROJECT, false, "", "", "", null, List.of(), "  ", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void askDirectiveNormalizesFinishFieldsToEmpty() {
    TurnDirective directive = new TurnDirective(
        TurnAction.ASK, "depth", "Java", Difficulty.MEDIUM, List.of("并发边界"),
        InterviewQuestionMode.PROJECT, false, "", "PLAN_FIRST_TURN", "", null, List.of(),
        "不应保留", "不应保留");

    assertThat(directive.finishReason()).isEmpty();
    assertThat(directive.unfinishedEvidence()).isEmpty();
  }

  @Test
  void legacySnapshotWithoutActionDefaultsToAsk() throws Exception {
    String legacyJson = """
        {"stageId":"depth","competency":"Java","difficulty":"MEDIUM",
         "evidenceTargets":["并发边界"],"questionMode":"PROJECT","ragEnabled":false,
         "probeFocus":"","reason":"LEGACY_PLAN_DERIVED","resumeEntryPoint":"",
         "retrievalPolicy":{"enabled":false,"scopes":[],"triggerKeywords":[],
           "allowedUses":[],"topK":null,"candidateCount":null,"minimumScore":null,
           "contextCharacterBudget":null},
         "coveredTopics":[]}
        """;

    TurnDirective directive = new ObjectMapper().readValue(legacyJson, TurnDirective.class);

    assertThat(directive.action()).isEqualTo(TurnAction.ASK);
    assertThat(directive.competency()).isEqualTo("Java");
  }

  @Test
  void finishFactoryProducesAuditableDirective() {
    TurnDirective directive = TurnDirective.finish(
        "ALL_COMPETENCIES_SETTLED", Difficulty.HARD, "MySQL（缺：索引依据）");

    assertThat(directive.action()).isEqualTo(TurnAction.FINISH);
    assertThat(directive.difficulty()).isEqualTo(Difficulty.HARD);
    assertThat(directive.finishReason()).isEqualTo("ALL_COMPETENCIES_SETTLED");
    assertThat(directive.unfinishedEvidence()).isEqualTo("MySQL（缺：索引依据）");
  }
}
