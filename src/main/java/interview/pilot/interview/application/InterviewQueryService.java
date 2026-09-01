package interview.pilot.interview.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.FixedInterviewReportResponse;
import interview.pilot.interview.api.InterviewHistoryResponse;
import interview.pilot.interview.api.InterviewReportStatusResponse;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.api.InterviewTurnView;
import interview.pilot.interview.domain.FixedInterviewReport;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InterviewQueryService {
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final InterviewReportRepository reports;
  private final AsyncTaskRepository tasks;
  private final ObjectMapper objectMapper;

  public InterviewQueryService(
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      InterviewReportRepository reports,
      AsyncTaskRepository tasks,
      ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.turns = turns;
    this.reports = reports;
    this.tasks = tasks;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public InterviewSessionResponse get(CurrentUser user, UUID sessionId) {
    return response(session(requireOwner(user), sessionId));
  }

  @Transactional(readOnly = true)
  public List<InterviewHistoryResponse> list(CurrentUser user) {
    return sessions.findAllByUserAccountIdOrderByCreatedAtDesc(requireOwner(user)).stream()
        .map(session -> new InterviewHistoryResponse(
            session.getSessionId(), session.getJobTitle(), session.getStatus(),
            session.getDifficulty(), session.getInterviewSize(), session.getInterviewMode(),
            session.getJobSourceType(),
            session.getCurrentTurnNo(), session.getCurrentMainQuestionNo(),
            session.getTotalMainQuestionCount(), session.getProviderId(),
            session.getModelName(), session.getSafeError(), session.getCreatedAt(),
            session.getCompletedAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public ReportQueryResult report(CurrentUser user, UUID sessionId) {
    Long ownerId = requireOwner(user);
    InterviewSessionEntity session = session(ownerId, sessionId);
    var task = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, ownerId).orElse(null);
    if (session.getStatus() == SessionStatus.COMPLETED) {
      if (task == null || task.getStatus() != AsyncTaskStatus.COMPLETED) throw inconsistent();
      var entity = reports.findBySessionId(session.getId()).orElseThrow(this::inconsistent);
      FixedInterviewReport report = decode(entity.getReportSnapshot(), FixedInterviewReport.class);
      if (!report.summary().equals(entity.getSummaryText())
          || report.overallScore() != entity.getOverallScore()) throw inconsistent();
      return new ReportQueryResult(HttpStatus.OK, new FixedInterviewReportResponse(
          sessionId, entity.getReportId(), report, entity.getCreatedAt()));
    }
    if ((session.getStatus() != SessionStatus.EVALUATING
        && session.getStatus() != SessionStatus.EVALUATION_FAILED) || task == null
        || task.getStatus() == AsyncTaskStatus.COMPLETED) throw inconsistent();
    boolean retryable = task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD;
    return new ReportQueryResult(HttpStatus.ACCEPTED, new InterviewReportStatusResponse(
        sessionId, session.getStatus(), task.getTaskId(), task.getStatus(),
        task.getLastError(), retryable));
  }

  private InterviewSessionResponse response(InterviewSessionEntity session) {
    InterviewBriefSnapshot brief = decode(session.getBriefSnapshot(), InterviewBriefSnapshot.class);
    var preparation = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
        "interview:" + session.getSessionId(), session.getUserAccountId()).orElse(null);
    List<InterviewTurnView> turnViews = turns.findAllBySessionIdOrderByTurnNo(session.getId())
        .stream().map(turn -> new InterviewTurnView(
            turn.getTurnNo(), turn.getPhase(), turn.getQuestionType(), turn.getQuestionText(),
            turn.getStatus(), turn.getAnswerText(), turn.getAskedAt(), turn.getAnsweredAt()))
        .toList();
    return new InterviewSessionResponse(
        session.getSessionId(), session.getResumeId(), brief.jobTitle(), brief.jobDescription(),
        session.getStatus(), session.getDifficulty(), session.getInterviewSize(),
        session.getInterviewMode(), session.getJobSourceType(), session.getCurrentTurnNo(),
        session.getCurrentMainQuestionNo(), session.getTotalMainQuestionCount(),
        session.getProviderId(), session.getModelName(),
        preparation == null ? null : preparation.getTaskId(), session.getSafeError(), turnViews);
  }

  private InterviewSessionEntity session(Long ownerId, UUID sessionId) {
    return sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(() -> new BusinessException(
            "INTERVIEW_NOT_FOUND", "Interview session not found", HttpStatus.NOT_FOUND));
  }

  private <T> T decode(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw inconsistent();
    }
  }

  private BusinessException inconsistent() {
    return new BusinessException(
        "INTERVIEW_STATE_INVALID", "Interview state is inconsistent", HttpStatus.CONFLICT);
  }

  private Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) throw new IllegalArgumentException("Authenticated user is required");
    return user.databaseId();
  }

  public record ReportQueryResult(HttpStatus status, Object body) { }
}
