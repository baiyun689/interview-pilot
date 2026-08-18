package interview.pilot.interview.strategy;

import java.util.List;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;
import interview.pilot.interview.skill.GroundingUse;

/**
 * 每轮的唯一动作指令，由 Strategy 依据 Plan + Progress + Assessment 重算产生。
 * ASK 必须携带目标能力与证据目标；FINISH 必须携带结束原因与未完成证据摘要。
 */
public record TurnDirective(
    TurnAction action,
    String stageId,
    String competency,
    Difficulty difficulty,
    List<String> evidenceTargets,
    InterviewQuestionMode questionMode,
    boolean ragEnabled,
    String probeFocus,
    String reason,
    String resumeEntryPoint,
    SkillRetrievalPolicy retrievalPolicy,
    List<String> coveredTopics,
    String finishReason,
    String unfinishedEvidence) {

  public TurnDirective {
    action = action == null ? TurnAction.ASK : action;
    if (difficulty == null) throw new IllegalArgumentException("difficulty is required");
    stageId = stageId == null ? "" : stageId.trim();
    competency = competency == null ? "" : competency.trim();
    evidenceTargets = evidenceTargets == null ? List.of() : List.copyOf(evidenceTargets);
    questionMode = questionMode == null ? InterviewQuestionMode.PROJECT : questionMode;
    probeFocus = probeFocus == null ? "" : probeFocus.trim();
    reason = reason == null ? "" : reason.trim();
    resumeEntryPoint = resumeEntryPoint == null ? "" : resumeEntryPoint.trim();
    retrievalPolicy = retrievalPolicy == null
        ? (ragEnabled ? new SkillRetrievalPolicy(true, List.of(), List.of(),
            List.of(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT))
            : SkillRetrievalPolicy.disabled())
        : retrievalPolicy;
    coveredTopics = coveredTopics == null ? List.of() : List.copyOf(coveredTopics);
    finishReason = finishReason == null ? "" : finishReason.trim();
    unfinishedEvidence = unfinishedEvidence == null ? "" : unfinishedEvidence.trim();
    if (action == TurnAction.ASK) {
      stageId = required(stageId, "stageId", 64);
      competency = required(competency, "competency", 100);
      if (evidenceTargets.isEmpty()) {
        throw new IllegalArgumentException("ASK requires evidence targets");
      }
      finishReason = "";
      unfinishedEvidence = "";
    } else {
      finishReason = required(finishReason, "finishReason", 200);
      stageId = "";
      competency = "";
      evidenceTargets = List.of();
      probeFocus = "";
      ragEnabled = false;
      retrievalPolicy = SkillRetrievalPolicy.disabled();
      coveredTopics = List.of();
    }
  }

  /** FINISH 指令工厂：结束原因 + 未完成证据摘要必须显式给出。 */
  public static TurnDirective finish(
      String finishReason, Difficulty difficulty, String unfinishedEvidence) {
    return new TurnDirective(
        TurnAction.FINISH, "", "", difficulty, List.of(),
        InterviewQuestionMode.PROJECT, false, "", "", "", SkillRetrievalPolicy.disabled(),
        List.of(), finishReason, unfinishedEvidence);
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String probeFocus, String reason,
      String resumeEntryPoint, SkillRetrievalPolicy retrievalPolicy, List<String> coveredTopics) {
    this(TurnAction.ASK, stageId, competency, difficulty, evidenceTargets, questionMode,
        ragEnabled, probeFocus, reason, resumeEntryPoint, retrievalPolicy, coveredTopics,
        "", "");
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String probeFocus, String reason,
      String resumeEntryPoint, SkillRetrievalPolicy retrievalPolicy) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        probeFocus, reason, resumeEntryPoint, retrievalPolicy, List.of());
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String probeFocus, String reason,
      String resumeEntryPoint) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        probeFocus, reason, resumeEntryPoint, null, List.of());
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String probeFocus, String reason) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        probeFocus, reason, "", null, List.of());
  }

  public TurnDirective(
      String stageId, String competency, Difficulty difficulty, List<String> evidenceTargets,
      InterviewQuestionMode questionMode, boolean ragEnabled, String reason) {
    this(stageId, competency, difficulty, evidenceTargets, questionMode, ragEnabled,
        "", reason, "", null, List.of());
  }

  private static String required(String value, String field, int max) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
