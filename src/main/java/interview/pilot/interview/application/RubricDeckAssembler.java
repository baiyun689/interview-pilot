package interview.pilot.interview.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.PreparedQuestionDeck;
import interview.pilot.interview.domain.RubricPoint;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;

/**
 * Deterministically merges stage-1 skeletons, the per-question RAG snapshots and the stage-3 LLM
 * rubric output into a validated {@link PreparedQuestionDeck}.
 *
 * <p>The LLM only proposes grounding/evidence; Java makes the final decision against the question's
 * own snapshot: a non-RETRIEVED snapshot is always GENERAL, illegal/foreign pointIds are scrubbed,
 * and KNOWLEDGE_ASSISTED survives only when the snapshot is retrieved, evidence is present and at
 * least one rubric point is traceable. This keeps grounding trustworthy and question-scoped.
 */
public class RubricDeckAssembler {

  public PreparedQuestionDeck assemble(
      List<QuestionSkeletonOutput.Skeleton> skeletons,
      Map<QuestionCardKey, RagContextSnapshot> snapshots,
      RubricOutput output) {
    Map<QuestionCardKey, RubricOutput.Item> items = index(output);
    List<PreparedQuestionDeck.PreparedQuestion> result = new ArrayList<>();

    for (QuestionSkeletonOutput.Skeleton skeleton : skeletons) {
      QuestionCardKey key = QuestionCardKey.of(skeleton.phase(), skeleton.sequence());
      RagContextSnapshot snapshot = snapshots.get(key);
      if (snapshot == null) {
        throw new InvalidQuestionDeckException("missing question RAG snapshot for " + key);
      }
      RubricOutput.Item item = items.get(key);
      if (item == null) {
        throw new InvalidQuestionDeckException("missing rubric item for " + key);
      }
      result.add(buildQuestion(skeleton, snapshot, item));
    }
    return new PreparedQuestionDeck(result);
  }

  private PreparedQuestionDeck.PreparedQuestion buildQuestion(
      QuestionSkeletonOutput.Skeleton skeleton,
      RagContextSnapshot snapshot,
      RubricOutput.Item item) {
    Set<String> legalPointIds = snapshot.chunks().stream()
        .map(RagContextSnapshot.Chunk::pointId)
        .collect(Collectors.toUnmodifiableSet());

    List<RubricPoint> points = item.rubric().stream()
        .map(data -> new RubricPoint(
            data.keyPoint(),
            data.acceptanceHint(),
            legalSource(legalPointIds, data.sourcePointId())))
        .toList();
    if (points.size() < 2 || points.size() > 4) {
      throw new InvalidQuestionDeckException(
          "rubric must contain 2 to 4 points for " + QuestionCardKey
              .of(skeleton.phase(), skeleton.sequence()));
    }

    List<String> evidenceRefs = item.evidenceRefs().stream()
        .filter(ref -> ref != null && legalPointIds.contains(ref))
        .distinct()
        .toList();

    boolean canGround = snapshot.status() == RagStatus.RETRIEVED;
    boolean wantsAssisted = item.groundingMode() == GroundingMode.KNOWLEDGE_ASSISTED;
    boolean hasTraceablePoint = points.stream().anyMatch(RubricPoint::grounded);
    boolean assisted = canGround && wantsAssisted && !evidenceRefs.isEmpty() && hasTraceablePoint;

    GroundingMode mode = assisted ? GroundingMode.KNOWLEDGE_ASSISTED : GroundingMode.GENERAL;
    List<String> finalRefs = assisted ? evidenceRefs : List.of();
    List<RubricPoint> finalPoints = assisted ? points : scrubSources(points);

    return new PreparedQuestionDeck.PreparedQuestion(
        skeleton.phase(),
        skeleton.sequence(),
        skeleton.topic(),
        skeleton.question(),
        skeleton.focusPoints(),
        skeleton.knowledgePoint(),
        skeleton.retrievalKeywords(),
        mode,
        finalRefs,
        finalPoints,
        skeleton.fallbackFollowUp());
  }

  private String legalSource(Set<String> legalPointIds, String sourcePointId) {
    // Immutable sets reject contains(null), so guard before membership lookup.
    return sourcePointId != null && legalPointIds.contains(sourcePointId) ? sourcePointId : null;
  }

  private List<RubricPoint> scrubSources(List<RubricPoint> points) {
    return points.stream()
        .map(point -> point.grounded()
            ? new RubricPoint(point.keyPoint(), point.acceptanceHint())
            : point)
        .toList();
  }

  private Map<QuestionCardKey, RubricOutput.Item> index(RubricOutput output) {
    if (output == null) {
      throw new InvalidQuestionDeckException("rubric output must not be null");
    }
    Map<QuestionCardKey, RubricOutput.Item> items = new HashMap<>();
    for (RubricOutput.Item item : output.items()) {
      if (item == null || item.phase() == null) {
        throw new InvalidQuestionDeckException("rubric item and phase must not be null");
      }
      QuestionCardKey key = QuestionCardKey.of(item.phase(), item.sequence());
      if (items.put(key, item) != null) {
        throw new InvalidQuestionDeckException("duplicate rubric item for " + key);
      }
    }
    return items;
  }
}
