package interview.pilot.interview.domain;

/** Decides the fixed-flow successor without counting follow-ups against main-question budgets. */
public final class FixedInterviewFlowPolicy {
  public Decision next(InterviewSize size, Progress progress) {
    if (size == null || progress == null || progress.phase() == null) {
      throw new IllegalArgumentException("interview flow progress is required");
    }
    if (progress.mainQuestionsAsked() < 0
        || progress.followUpsForCurrentCard() < 0
        || progress.currentCardFollowUpQuota() < 0) {
      throw new IllegalArgumentException("interview flow counts cannot be negative");
    }

    InterviewPhase phase = progress.phase();
    if (phase == InterviewPhase.SELF_INTRODUCTION) {
      return Decision.main(InterviewPhase.FUNDAMENTALS, 1);
    }
    if (progress.followUpsForCurrentCard() < progress.currentCardFollowUpQuota()) {
      return Decision.followUp(phase);
    }
    if (progress.mainQuestionsAsked() < size.mainQuestionCount(phase)) {
      return Decision.main(phase, progress.mainQuestionsAsked() + 1);
    }
    return switch (phase) {
      case FUNDAMENTALS -> Decision.main(InterviewPhase.PROJECT_EXPERIENCE, 1);
      case PROJECT_EXPERIENCE -> Decision.main(InterviewPhase.SCENARIO_TRADEOFF, 1);
      case SCENARIO_TRADEOFF -> Decision.end();
      case SELF_INTRODUCTION -> throw new IllegalStateException("self introduction handled above");
    };
  }

  public record Progress(
      InterviewPhase phase,
      int mainQuestionsAsked,
      int followUpsForCurrentCard,
      int currentCardFollowUpQuota) { }

  public record Decision(Kind kind, InterviewPhase phase, int mainQuestionSequence) {
    public static Decision main(InterviewPhase phase, int sequence) {
      return new Decision(Kind.MAIN, phase, sequence);
    }

    public static Decision followUp(InterviewPhase phase) {
      return new Decision(Kind.FOLLOW_UP, phase, 0);
    }

    public static Decision end() {
      return new Decision(Kind.END, null, 0);
    }
  }

  public enum Kind { MAIN, FOLLOW_UP, END }
}
