package interview.pilot.interview.domain;

/**
 * Lifecycle of a single turn's asynchronous answer evaluation (stored on
 * {@code interview_turn.eval_status}).
 *
 * <p>The evaluation runs off the answer critical path, so the report barrier treats every state
 * except {@link #PENDING} as a terminal state: a finished evaluation (OK / GENERAL_FALLBACK), a
 * deliberately skipped self-introduction (SKIPPED), a turn produced while the feature was off
 * (NOT_REQUIRED), and an exhausted failure (FAILED) all let the report proceed.
 */
public enum EvalStatus {
  /** The ANSWER_EVALUATION outbox task was created and the answer has not been judged yet. */
  PENDING,
  /** Judged against the card's KNOWLEDGE_ASSISTED rubric plus retrieved reference. */
  OK,
  /** Judged from general rubric points only (no usable retrieved reference for this card). */
  GENERAL_FALLBACK,
  /** Retries were exhausted; the report falls back to the raw question/answer text. */
  FAILED,
  /** Self-introduction turns are never evaluated. */
  SKIPPED,
  /** Turn answered while answer evaluation was disabled, or a historical backfilled row. */
  NOT_REQUIRED;

  public boolean terminal() {
    return this != PENDING;
  }
}
