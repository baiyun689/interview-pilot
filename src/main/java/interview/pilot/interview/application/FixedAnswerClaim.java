package interview.pilot.interview.application;

public record FixedAnswerClaim(
    int turnNo,
    boolean owner,
    FixedAnswerResult replay,
    FixedAnswerService.Work work) { }
