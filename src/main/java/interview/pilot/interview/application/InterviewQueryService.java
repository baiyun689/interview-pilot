package interview.pilot.interview.application;

import java.util.UUID;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.api.InterviewHistoryResponse;
import interview.pilot.interview.api.InterviewReportResponse;
import interview.pilot.interview.api.InterviewReportStatusResponse;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.interview.skill.SkillSnapshot;

@Service
public class InterviewQueryService {
  private final InterviewSessionRepository sessions;
  private final JobProfileRepository jobs;
  private final InterviewTurnRepository turns;
  private final InterviewResponseMapper mapper;
  private final InterviewReportRepository reports;
  private final AsyncTaskRepository tasks;
  private final StoredInterviewReportCodec reportCodec;
  private final ObjectMapper objectMapper;

  public InterviewQueryService(
      InterviewSessionRepository sessions,
      JobProfileRepository jobs,
      InterviewTurnRepository turns,
      InterviewResponseMapper mapper,
      InterviewReportRepository reports,
      AsyncTaskRepository tasks,
      StoredInterviewReportCodec reportCodec,
      ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.jobs = jobs;
    this.turns = turns;
    this.mapper = mapper;
    this.reports = reports;
    this.tasks = tasks;
    this.reportCodec = reportCodec;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public InterviewSessionResponse get(CurrentUser user, UUID sessionId) {
    var session = session(user, sessionId);
    var job = jobs.findById(session.getJobProfileId())
        .orElseThrow(() -> new IllegalStateException("Interview job profile is missing"));
    return mapper.map(session, job, turns.findAllBySessionIdOrderByTurnNo(session.getId()));
  }

  @Deprecated(forRemoval = true)
  public InterviewSessionResponse get(UUID sessionId) { return get(legacyUser(), sessionId); }

  @Transactional(readOnly = true)
  public List<InterviewHistoryResponse> list(CurrentUser user) {
    return sessions.findAllByUserAccountIdOrderByCreatedAtDesc(requireOwner(user)).stream().map(session -> {
      var job = jobs.findById(session.getJobProfileId())
          .orElseThrow(() -> new IllegalStateException("Interview job profile is missing"));
      SkillSnapshot skill = readSkill(job.getSkillSnapshot());
      return new InterviewHistoryResponse(
          session.getSessionId(), job.getTitle(), session.getStatus(), session.getDifficulty(),
          session.getCurrentTurnNo(), session.getTotalTurnBudget(), session.getProviderId(),
          session.getModelName(), session.getCreatedAt(), session.getCompletedAt(),
          skill.id(), skill.name());
    }).toList();
  }

  @Deprecated(forRemoval = true)
  public List<InterviewHistoryResponse> list() { return list(legacyUser()); }

  @Transactional(readOnly = true)
  public ReportQueryResult report(CurrentUser user, UUID sessionId) {
    Long ownerId = requireOwner(user);
    var session = session(ownerId, sessionId);
    var task = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, ownerId).orElse(null);
    if (session.getStatus() == SessionStatus.COMPLETED) {
      if (task == null || task.getStatus() != AsyncTaskStatus.COMPLETED) throw inconsistent();
      var entity = reports.findBySessionId(session.getId()).orElseThrow(this::inconsistent);
      interview.pilot.interview.domain.InterviewReport report;
      try {
        report = reportCodec.read(entity.getReportSnapshot(), sessionId);
      } catch (IllegalStateException exception) {
        throw inconsistent();
      }
      if (!report.summary().equals(entity.getSummaryText())
          || scoreFrom(entity.getScoreSnapshot()) != report.overallScore()) {
        throw inconsistent();
      }
      return new ReportQueryResult(HttpStatus.OK, new InterviewReportResponse(
          sessionId, entity.getReportId(), report, entity.getCreatedAt()));
    }
    if (session.getStatus() != SessionStatus.EVALUATING || task == null
        || task.getStatus() == AsyncTaskStatus.COMPLETED) {
      throw inconsistent();
    }
    boolean retryable = task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD;
    return new ReportQueryResult(HttpStatus.ACCEPTED, new InterviewReportStatusResponse(
        sessionId, session.getStatus(), task.getTaskId(), task.getStatus(),
        task.getLastError(), retryable));
  }

  @Deprecated(forRemoval = true)
  public ReportQueryResult report(UUID sessionId) { return report(legacyUser(), sessionId); }

  private int scoreFrom(String snapshot) {
    try {
      var node = objectMapper.readTree(snapshot);
      if (node == null || node.get("overallScore") == null
          || !node.get("overallScore").isInt()) throw inconsistent();
      return node.get("overallScore").asInt();
    } catch (JacksonException exception) {
      throw inconsistent();
    }
  }

  private SkillSnapshot readSkill(String snapshot) {
    try {
      SkillSnapshot skill = objectMapper.readValue(snapshot, SkillSnapshot.class);
      if (skill == null) throw new IllegalStateException("Stored interview skill is invalid");
      return skill;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored interview skill is invalid", exception);
    }
  }

  private BusinessException inconsistent() {
    return new BusinessException(
        "INTERVIEW_REPORT_STATE_INVALID", "Interview report state is inconsistent",
        HttpStatus.CONFLICT);
  }

  private interview.pilot.interview.infrastructure.InterviewSessionEntity session(
      CurrentUser user, UUID sessionId) {
    return session(requireOwner(user), sessionId);
  }

  private interview.pilot.interview.infrastructure.InterviewSessionEntity session(
      Long ownerId, UUID sessionId) {
    return sessions.findBySessionIdAndUserAccountId(sessionId, ownerId)
        .orElseThrow(() -> new BusinessException(
            "INTERVIEW_NOT_FOUND", "Interview session not found", HttpStatus.NOT_FOUND));
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private static CurrentUser legacyUser() {
    return new CurrentUser(1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  }

  public record ReportQueryResult(HttpStatus status, Object body) {}
}
