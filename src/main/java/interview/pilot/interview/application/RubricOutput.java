package interview.pilot.interview.application;

import java.util.List;

import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InterviewPhase;

/**
 * Raw LLM output of stage 3 (per-question rubric). Each item aligns with a skeleton by
 * (phase, sequence) and proposes a grounding mode, evidence references and scorable points.
 * The final grounding decision is re-checked deterministically in {@link RubricDeckAssembler}.
 */
public record RubricOutput(int schemaVersion, List<Item> items) {

  public RubricOutput {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      InterviewPhase phase,
      int sequence,
      GroundingMode groundingMode,
      List<String> evidenceRefs,
      List<RubricPointData> rubric) {

    public Item {
      evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
      rubric = rubric == null ? List.of() : List.copyOf(rubric);
    }
  }

  public record RubricPointData(String keyPoint, String acceptanceHint, String sourcePointId) { }
}
