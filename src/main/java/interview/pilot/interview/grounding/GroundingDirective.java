package interview.pilot.interview.grounding;

import java.util.List;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;
import interview.pilot.interview.skill.GroundingUse;
import interview.pilot.interview.strategy.TurnDirective;

public record GroundingDirective(
    boolean enabled, KnowledgeRole role, String competency, Difficulty difficulty,
    InterviewQuestionMode questionMode, List<String> evidenceGaps, String probeFocus,
    List<String> coveredTopics, List<String> scopes, List<String> triggerKeywords,
    interview.pilot.interview.skill.SkillRetrievalPolicy policy) {

  public GroundingDirective {
    role = role == null ? KnowledgeRole.TECHNICAL_REFERENCE : role;
    evidenceGaps = copy(evidenceGaps);
    coveredTopics = copy(coveredTopics);
    scopes = copy(scopes);
    triggerKeywords = copy(triggerKeywords);
    policy = policy == null ? interview.pilot.interview.skill.SkillRetrievalPolicy.disabled() : policy;
    probeFocus = probeFocus == null ? "" : probeFocus.trim();
  }

  public static GroundingDirective from(TurnDirective turn) {
    return from(turn, "");
  }

  /**
   * Builds a retrieval request for the next action and carries a small set of
   * terms actually mentioned by the candidate. This is the answer-triggered
   * part of RAG: the answer is data for retrieval, never an instruction.
   */
  public static GroundingDirective from(TurnDirective turn, String answer) {
    SkillRetrievalPolicy policy = turn.retrievalPolicy();
    return new GroundingDirective(
        turn.ragEnabled() && policy.enabled(), KnowledgeRole.TECHNICAL_REFERENCE,
        turn.competency(), turn.difficulty(), turn.questionMode(), turn.evidenceTargets(),
        turn.probeFocus(), turn.coveredTopics(), policy.scopes(), answerKeywords(answer), policy);
  }

  /** One bounded retrieval used as material for the pre-interview question deck. */
  public static GroundingDirective forQuestionDeck(
      Difficulty difficulty, InterviewPlan plan) {
    var policy = new SkillRetrievalPolicy(true, List.of(),
        List.of(GroundingUse.GENERATE_SCENARIO, GroundingUse.VERIFY_FACT));
    return new GroundingDirective(
        true, KnowledgeRole.TECHNICAL_REFERENCE, "Java 后端", difficulty,
        InterviewQuestionMode.MECHANISM, plan.competencies(), "", List.of(), List.of(),
        List.of("Java", "Spring", "MySQL", "Redis", "并发", "分布式"), policy);
  }

  private static List<String> answerKeywords(String answer) {
    if (answer == null || answer.isBlank()) return List.of();
    String normalized = answer.replaceAll("[^\\p{L}\\p{N}+#._-]", " ");
    return java.util.Arrays.stream(normalized.split("\\s+"))
        .filter(value -> value.length() >= 2 && value.length() <= 48)
        .filter(value -> value.matches(".*[A-Za-z0-9+#].*") || value.length() >= 3)
        .distinct().limit(12).toList();
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
  }
}
