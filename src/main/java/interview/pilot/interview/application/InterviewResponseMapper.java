package interview.pilot.interview.application;

import java.util.List;

import org.springframework.stereotype.Component;

import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.skill.SkillSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class InterviewResponseMapper {
  private final ObjectMapper objectMapper;
  private final StoredAnswerResultCodec resultCodec;

  public InterviewResponseMapper(
      ObjectMapper objectMapper, StoredAnswerResultCodec resultCodec) {
    this.objectMapper = objectMapper;
    this.resultCodec = resultCodec;
  }

  public InterviewSessionResponse map(
      InterviewSessionEntity session,
      JobProfileEntity job,
      List<InterviewTurnEntity> turns) {
    return map(session, job, turns, List.of());
  }

  public InterviewSessionResponse map(
      InterviewSessionEntity session,
      JobProfileEntity job,
      List<InterviewTurnEntity> turns,
      List<InterviewSessionResponse.KnowledgeBaseSummary> knowledgeBases) {
    InterviewPlan plan;
    SkillSnapshot skill;
    try {
      plan = objectMapper.readValue(session.getPlanSnapshot(), InterviewPlan.class);
      skill = objectMapper.readValue(job.getSkillSnapshot(), SkillSnapshot.class);
    } catch (JacksonException exception) {
      throw invalidStoredPlan();
    }
    if (plan == null || skill == null || plan.totalTurnBudget() != session.getTotalTurnBudget()) {
      throw invalidStoredPlan();
    }
    return new InterviewSessionResponse(
        session.getSessionId(), session.getResumeId(), job.getTitle(), job.getDescriptionText(),
        session.getStatus(), session.getDifficulty(), session.getCurrentTurnNo(),
        session.getTotalTurnBudget(), session.getProviderId(), session.getModelName(), plan,
        turns.stream().map(turn -> mapTurn(session, turn)).toList(),
        skill.id(), skill.name(), skill.version(),
        knowledgeBases);
  }

  private InterviewSessionResponse.TurnResponse mapTurn(
      InterviewSessionEntity session, InterviewTurnEntity turn) {
    AnswerProcessingResult result = null;
    if (turn.getStatus() == interview.pilot.interview.domain.TurnStatus.COMPLETED) {
      result = resultCodec.readCompleted(
          turn.getEvaluationSnapshot(), session.getSessionId(), turn.getRequestId(), turn.getTurnNo());
    }
    return new InterviewSessionResponse.TurnResponse(
        turn.getRequestId(), turn.getTurnNo(), turn.getStatus(), turn.getDifficulty(),
        turn.getQuestionText(), turn.getTargetCompetency(), turn.getAskedAt(),
        turn.getAnswerText(), result == null ? turn.getFeedbackText() : result.evaluation().feedback(),
        result == null ? null : result.evaluation().score(),
        result == null ? List.of() : result.evaluation().evidence(),
        result == null ? List.of() : result.evaluation().missingPoints(),
        result == null ? null : result.decision(),
        result == null ? null : result.nextDifficulty(), turn.getAnsweredAt(),
        turn.getProcessingError());
  }

  private IllegalStateException invalidStoredPlan() {
    return new IllegalStateException("Stored interview plan is invalid");
  }
}
