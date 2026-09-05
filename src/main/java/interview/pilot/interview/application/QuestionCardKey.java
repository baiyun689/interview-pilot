package interview.pilot.interview.application;

import java.util.Objects;

import interview.pilot.interview.domain.InterviewPhase;

/** Identifies a single question card within a session by its phase and per-phase sequence. */
public record QuestionCardKey(InterviewPhase phase, int sequence) {

  public QuestionCardKey {
    Objects.requireNonNull(phase, "phase is required");
    if (sequence < 1) {
      throw new IllegalArgumentException("card sequence must start at 1");
    }
  }

  public static QuestionCardKey of(InterviewPhase phase, int sequence) {
    return new QuestionCardKey(phase, sequence);
  }
}
