package interview.pilot.interview.grounding;

import java.util.List;

import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.skill.InterviewQuestionMode;
import interview.pilot.interview.skill.SkillRetrievalPolicy;
import interview.pilot.interview.strategy.TurnDirective;

public record GroundingDirective(
    boolean enabled, KnowledgeRole role, String competency, Difficulty difficulty,
    InterviewQuestionMode questionMode, List<String> evidenceGaps, String probeFocus,
    List<String> coveredTopics, List<String> scopes, List<String> triggerKeywords,
    List<String> allowedUses) {

  public GroundingDirective {
    role = role == null ? KnowledgeRole.TECHNICAL_REFERENCE : role;
    evidenceGaps = copy(evidenceGaps);
    coveredTopics = copy(coveredTopics);
    scopes = copy(scopes);
    triggerKeywords = copy(triggerKeywords);
    allowedUses = copy(allowedUses);
    probeFocus = probeFocus == null ? "" : probeFocus.trim();
  }

  public static GroundingDirective from(TurnDirective turn, List<String> coveredTopics) {
    SkillRetrievalPolicy policy = turn.retrievalPolicy();
    return new GroundingDirective(
        turn.ragEnabled() && policy.enabled(), KnowledgeRole.TECHNICAL_REFERENCE,
        turn.competency(), turn.difficulty(), turn.questionMode(), turn.evidenceTargets(),
        turn.probeFocus(), coveredTopics, policy.scopes(), policy.triggerKeywords(),
        policy.allowedUses());
  }

  private static List<String> copy(List<String> values) {
    return values == null ? List.of() : values.stream()
        .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
  }
}
