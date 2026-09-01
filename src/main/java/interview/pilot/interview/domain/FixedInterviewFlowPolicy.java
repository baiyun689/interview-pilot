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

  public sealed interface Decision permits MainQuestion, FollowUp, End {
    static MainQuestion main(InterviewPhase phase, int sequence) {
      return new MainQuestion(phase, sequence);
    }

    static FollowUp followUp(InterviewPhase phase) {
      return new FollowUp(phase);
    }

    static End end() {
      return new End();
    }
  }

  public record MainQuestion(InterviewPhase phase, int sequence) implements Decision {
    public MainQuestion {
      if (phase == null || phase == InterviewPhase.SELF_INTRODUCTION || sequence < 1) {
        throw new IllegalArgumentException("main question decision is invalid");
      }
    }
  }

  public record FollowUp(InterviewPhase phase) implements Decision {
    public FollowUp {
      if (phase == null || !phase.allowsFollowUp()) {
        throw new IllegalArgumentException("follow-up decision is invalid");
      }
    }
  }

  public record End() implements Decision { }
}
