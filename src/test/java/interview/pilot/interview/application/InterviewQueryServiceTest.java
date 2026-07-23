package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.InterviewReportResponse;
import interview.pilot.interview.api.InterviewReportStatusResponse;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewReport;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewReportEntity;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileEntity;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import tools.jackson.databind.ObjectMapper;

class InterviewQueryServiceTest {
  private InterviewSessionRepository sessions;
  private JobProfileRepository jobs;
  private InterviewTurnRepository turns;
  private InterviewReportRepository reports;
  private AsyncTaskRepository tasks;
  private StoredInterviewReportCodec codec;
  private InterviewQueryService service;

  @BeforeEach
  void setUp() {
    sessions = mock(InterviewSessionRepository.class);
    jobs = mock(JobProfileRepository.class);
    turns = mock(InterviewTurnRepository.class);
    reports = mock(InterviewReportRepository.class);
    tasks = mock(AsyncTaskRepository.class);
    ObjectMapper objectMapper = new ObjectMapper();
    codec = new StoredInterviewReportCodec(objectMapper);
    service = new InterviewQueryService(
        sessions, jobs, turns, mock(InterviewResponseMapper.class), reports, tasks, codec,
        objectMapper);
  }

  @Test
  void evaluatingFailureReturnsAcceptedSafeStatusAndRetryHint() {
    UUID sessionId = UUID.randomUUID();
    InterviewSessionEntity session = session(sessionId, SessionStatus.EVALUATING);
    AsyncTaskEntity task = task(UUID.randomUUID(), AsyncTaskStatus.DEAD);
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 1L)).thenReturn(Optional.of(session));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, 1L))
        .thenReturn(Optional.of(task));

    var result = service.report(sessionId);

    assertThat(result.status()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(result.body()).isInstanceOfSatisfying(
        InterviewReportStatusResponse.class, body -> {
          assertThat(body.taskStatus()).isEqualTo(AsyncTaskStatus.DEAD);
          assertThat(body.error()).isEqualTo("safe error");
          assertThat(body.retryable()).isTrue();
        });
  }

  @Test
  void completedReportValidatesStoredIdentitySummaryAndScoreBeforeReturning() {
    UUID sessionId = UUID.randomUUID();
    InterviewSessionEntity session = session(sessionId, SessionStatus.COMPLETED);
    AsyncTaskEntity task = task(UUID.randomUUID(), AsyncTaskStatus.COMPLETED);
    InterviewReport report = new InterviewReport(
        91, Map.of("Java", 92), List.of("Evidence"), List.of("Depth"), "Summary");
    InterviewReportEntity entity = mock(InterviewReportEntity.class);
    when(entity.getReportSnapshot()).thenReturn(codec.write(sessionId, report));
    when(entity.getSummaryText()).thenReturn("Summary");
    when(entity.getScoreSnapshot()).thenReturn("{\"overallScore\":91}");
    when(entity.getReportId()).thenReturn(UUID.randomUUID());
    when(entity.getCreatedAt()).thenReturn(Instant.parse("2026-07-13T10:00:00Z"));
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 1L)).thenReturn(Optional.of(session));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, 1L))
        .thenReturn(Optional.of(task));
    when(reports.findBySessionId(10L)).thenReturn(Optional.of(entity));

    var result = service.report(sessionId);

    assertThat(result.status()).isEqualTo(HttpStatus.OK);
    assertThat(result.body()).isInstanceOfSatisfying(
        InterviewReportResponse.class, body -> assertThat(body.report()).isEqualTo(report));
  }

  @Test
  void mismatchedScoreNeverLeaksAStoredReport() {
    UUID sessionId = UUID.randomUUID();
    InterviewSessionEntity session = session(sessionId, SessionStatus.COMPLETED);
    AsyncTaskEntity task = task(UUID.randomUUID(), AsyncTaskStatus.COMPLETED);
    InterviewReport report = new InterviewReport(
        91, Map.of("Java", 92), List.of("Evidence"), List.of("Depth"), "Summary");
    InterviewReportEntity entity = mock(InterviewReportEntity.class);
    when(entity.getReportSnapshot()).thenReturn(codec.write(sessionId, report));
    when(entity.getSummaryText()).thenReturn("Summary");
    when(entity.getScoreSnapshot()).thenReturn("{\"overallScore\":12}");
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 1L)).thenReturn(Optional.of(session));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, 1L))
        .thenReturn(Optional.of(task));
    when(reports.findBySessionId(10L)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.report(sessionId))
        .isInstanceOf(BusinessException.class)
        .hasMessage("Interview report state is inconsistent");
  }

  @Test
  void invalidReportSnapshotVariantsAreSanitizedAsStateConflict() {
    UUID sessionId = UUID.randomUUID();
    InterviewSessionEntity session = session(sessionId, SessionStatus.COMPLETED);
    AsyncTaskEntity task = task(UUID.randomUUID(), AsyncTaskStatus.COMPLETED);
    InterviewReportEntity entity = mock(InterviewReportEntity.class);
    when(entity.getSummaryText()).thenReturn("Summary");
    when(entity.getScoreSnapshot()).thenReturn("{\"overallScore\":91}");
    when(sessions.findBySessionIdAndUserAccountId(sessionId, 1L)).thenReturn(Optional.of(session));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.INTERVIEW_EVALUATION, "interview:" + sessionId, 1L))
        .thenReturn(Optional.of(task));
    when(reports.findBySessionId(10L)).thenReturn(Optional.of(entity));
    InterviewReport valid = new InterviewReport(
        91, Map.of("Java", 92), List.of("Evidence"), List.of("Depth"), "Summary");

    for (String invalid : List.of(
        "", "not-json", codec.write(UUID.randomUUID(), valid))) {
      when(entity.getReportSnapshot()).thenReturn(invalid.isEmpty() ? null : invalid);
      assertThatThrownBy(() -> service.report(sessionId))
          .isInstanceOf(BusinessException.class)
          .hasMessage("Interview report state is inconsistent");
    }
  }

  @Test
  void historyPreservesRepositoryCreatedAtDescendingOrder() {
    InterviewSessionEntity newest = session(UUID.randomUUID(), SessionStatus.INTERVIEWING);
    InterviewSessionEntity oldest = session(UUID.randomUUID(), SessionStatus.COMPLETED);
    JobProfileEntity newestJob = mock(JobProfileEntity.class);
    JobProfileEntity oldestJob = mock(JobProfileEntity.class);
    when(newestJob.getTitle()).thenReturn("Newest");
    when(oldestJob.getTitle()).thenReturn("Oldest");
    String skillSnapshot = """
        {"id":"java-backend","name":"Java Backend","description":"",\
        "group":"JOB","defaultCompetencies":[],"persona":"","rubric":"",\
        "references":[],"version":"1"}
        """;
    when(newestJob.getSkillSnapshot()).thenReturn(skillSnapshot);
    when(oldestJob.getSkillSnapshot()).thenReturn(skillSnapshot);
    when(sessions.findAllByUserAccountIdOrderByCreatedAtDesc(1L))
        .thenReturn(List.of(newest, oldest));
    when(jobs.findById(20L)).thenReturn(Optional.of(newestJob));
    when(jobs.findById(21L)).thenReturn(Optional.of(oldestJob));

    assertThat(service.list()).extracting("jobTitle")
        .containsExactly("Newest", "Oldest");
  }

  private InterviewSessionEntity session(UUID publicId, SessionStatus status) {
    InterviewSessionEntity session = mock(InterviewSessionEntity.class);
    when(session.getId()).thenReturn(10L);
    when(session.getSessionId()).thenReturn(publicId);
    when(session.getStatus()).thenReturn(status);
    when(session.getDifficulty()).thenReturn(Difficulty.MEDIUM);
    when(session.getJobProfileId()).thenReturn(status == SessionStatus.INTERVIEWING ? 20L : 21L);
    when(session.getProviderId()).thenReturn("deepseek");
    when(session.getModelName()).thenReturn("deepseek-chat");
    when(session.getCreatedAt()).thenReturn(Instant.parse("2026-07-13T10:00:00Z"));
    return session;
  }

  private AsyncTaskEntity task(UUID taskId, AsyncTaskStatus status) {
    AsyncTaskEntity task = mock(AsyncTaskEntity.class);
    when(task.getTaskId()).thenReturn(taskId);
    when(task.getStatus()).thenReturn(status);
    when(task.getLastError()).thenReturn("safe error");
    return task;
  }
}
