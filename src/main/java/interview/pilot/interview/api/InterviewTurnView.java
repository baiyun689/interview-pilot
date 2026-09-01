package interview.pilot.interview.api;

import java.time.Instant;

import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.domain.TurnStatus;

public record InterviewTurnView(
    int turnNo,
    InterviewPhase phase,
    QuestionType questionType,
    String question,
    TurnStatus status,
    String answer,
    Instant askedAt,
    Instant answeredAt) { }
