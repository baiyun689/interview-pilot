package interview.pilot.interview.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FixedInterviewReport(
    int overallScore,
    Map<InterviewPhase, Integer> phaseScores,
    List<String> strengths,
    List<String> improvements,
    List<TechnicalReference> technicalReferences,
    List<String> conflictNotes,
    String summary,
    Map<InterviewPhase, String> ragAvailability) {

  public FixedInterviewReport {
    if (overallScore < 0 || overallScore > 100) throw new IllegalArgumentException("invalid overallScore");
    phaseScores = phaseScores == null ? Map.of() : Map.copyOf(phaseScores);
    phaseScores.forEach((phase, score) -> {
      if (phase == null || score == null || score < 0 || score > 100) {
        throw new IllegalArgumentException("invalid phaseScores");
      }
    });
    strengths = items(strengths, true, "strengths");
    improvements = items(improvements, true, "improvements");
    technicalReferences = technicalReferences == null ? List.of() : List.copyOf(technicalReferences);
    conflictNotes = items(conflictNotes, false, "conflictNotes");
    summary = text(summary, 4_000, "summary");
    ragAvailability = ragAvailability == null ? Map.of() : Map.copyOf(ragAvailability);
  }

  public record TechnicalReference(String sourceId, String note) {
    public TechnicalReference {
      sourceId = text(sourceId, 200, "sourceId");
      note = text(note, 500, "technical reference note");
    }
  }

  private static List<String> items(List<String> values, boolean required, String field) {
    if (values == null || (required && values.isEmpty()) || values.size() > 30) {
      throw new IllegalArgumentException("invalid " + field);
    }
    return values.stream().map(value -> text(value, 500, field)).toList();
  }

  private static String text(String value, int max, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > max) {
      throw new IllegalArgumentException("invalid " + field);
    }
    return normalized;
  }
}
