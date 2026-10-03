package interview.pilot.interview.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.ai.AiStructuredOutputException;
import interview.pilot.async.domain.AsyncTaskStatus;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.messaging.TaskMessage;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.EvalStatus;
import interview.pilot.interview.domain.FixedInterviewReport;
import interview.pilot.interview.domain.FixedInterviewEvidencePolicy;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewReportEntity;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagContextSnapshot;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class FixedInterviewReportHandler {
  @org.springframework.beans.factory.annotation.Value("${app.async.interview-report.lease-duration:11m}")
  private Duration leaseDuration = Duration.ofMinutes(11);
  private final FixedReportGenerator generator;
  private final AsyncTaskRepository tasks;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final InterviewQuestionCardRepository cards;
  private final InterviewReportRepository reports;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate transactions;
  private final AnswerEvaluationProperties evaluationProperties;
  private final FixedInterviewEvidencePolicy evidencePolicy = new FixedInterviewEvidencePolicy();
  private final ReportEvaluationBarrier evaluationBarrier = new ReportEvaluationBarrier();

  public FixedInterviewReportHandler(
      FixedReportGenerator generator,
      AsyncTaskRepository tasks,
      InterviewSessionRepository sessions,
      InterviewTurnRepository turns,
      InterviewQuestionCardRepository cards,
      InterviewReportRepository reports,
      ObjectMapper objectMapper,
      AnswerEvaluationProperties evaluationProperties,
      PlatformTransactionManager transactionManager) {
    this.generator = generator;
    this.tasks = tasks;
    this.sessions = sessions;
    this.turns = turns;
    this.cards = cards;
    this.reports = reports;
    this.objectMapper = objectMapper;
    this.evaluationProperties = evaluationProperties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Outcome handle(TaskMessage message, int completedRetries) {
    Begin begin = transactions.execute(status -> begin(message, completedRetries));
    if (begin.skipped() != null) return begin.skipped();
    if (begin.waiting()) throw new ReportGenerationRetryableException(begin.attemptGeneration(), begin.executionToken());
    try {
      FixedInterviewReport report = generator.generate(
          begin.providerId(), begin.modelName(), begin.input());
      validate(report, begin.allowedSourceIds(), begin.ragAvailability(), begin.input());
      return transactions.execute(status -> complete(message, begin, report));
    } catch (AiStructuredOutputException | IllegalArgumentException exception) {
      String category = exception instanceof InvalidReport invalid ? invalid.category
          : exception instanceof AiStructuredOutputException ? "STRUCTURE" : "CONTENT";
      return transactions.execute(status -> failInvalid(message, begin, category));
    } catch (RuntimeException exception) {
      boolean current = Boolean.TRUE.equals(transactions.execute(status -> recordRetryable(message, begin)));
      if (!current) return Outcome.STALE;
      throw new ReportGenerationRetryableException(begin.attemptGeneration(), begin.executionToken(), exception);
    }
  }

  public Target inspect(TaskMessage message) {
    return transactions.execute(status -> {
      AsyncTaskEntity task = task(message);
      InterviewSessionEntity session = session(task);
      boolean terminal = task.getExecutionEpoch() != message.executionEpoch()
          || task.getStatus() == AsyncTaskStatus.COMPLETED
          || task.getStatus() == AsyncTaskStatus.FAILED
          || task.getStatus() == AsyncTaskStatus.DEAD
          || session.getStatus() == SessionStatus.COMPLETED
          || session.getStatus() == SessionStatus.EVALUATION_FAILED;
      return new Target(session.getSessionId(), terminal, task.getAttemptCount());
    });
  }

  public boolean markDead(TaskMessage message, int attemptGeneration, String executionToken) {
    Boolean result = transactions.execute(status -> {
      AsyncTaskEntity task = lockedTask(message);
      InterviewSessionEntity session = lockedSession(task);
      if (!task.ownsExecution(message.executionEpoch(), attemptGeneration, executionToken)
          || session.getStatus() != SessionStatus.EVALUATING) return false;
      task.setStatus(AsyncTaskStatus.DEAD);
      task.clearExecutionLease();
      task.setLastError("INTERVIEW_REPORT_RETRY_EXHAUSTED");
      session.evaluationFailed("INTERVIEW_REPORT_RETRY_EXHAUSTED");
      return true;
    });
    return Boolean.TRUE.equals(result);
  }

  private Begin begin(TaskMessage message, int completedRetries) {
    AsyncTaskEntity task = task(message);
    if (task.getExecutionEpoch() != message.executionEpoch()
        || task.getStatus() == AsyncTaskStatus.COMPLETED || task.getStatus() == AsyncTaskStatus.FAILED
        || task.getStatus() == AsyncTaskStatus.DEAD) return Begin.skipped(Outcome.STALE);
    String token = UUID.randomUUID().toString();
    if (tasks.claimExecution(message.taskId().toString(), AsyncTaskType.INTERVIEW_EVALUATION.name(),
        message.bizKey(), message.executionEpoch(), token,
        interview.pilot.async.infrastructure.ExecutionLeaseDuration.seconds(leaseDuration)) != 1)
      return Begin.skipped(Outcome.BUSY);
    task = task(message);
    InterviewSessionEntity session = lockedSession(task);
    if (session.getStatus() != SessionStatus.EVALUATING
        || reports.findBySessionId(session.getId()).isPresent()) {
      throw new IllegalStateException("Interview report state is inconsistent");
    }

    InterviewBriefSnapshot brief = decode(session.getBriefSnapshot(), InterviewBriefSnapshot.class);
    var storedTurns = turns.findAllBySessionIdOrderByTurnNo(session.getId());
    var storedCards = cards.findAllBySessionIdOrderByPhaseAscPhaseSequenceAsc(session.getId());
    if (!session.isRecruitment()) evidencePolicy.requireComplete(
        brief.totalMainQuestionCount(),
        storedCards.stream().map(card -> new FixedInterviewEvidencePolicy.CardEvidence(
            card.getId(), card.getPhase(), card.getFollowUpQuota())).toList(),
        storedTurns.stream().map(turn -> new FixedInterviewEvidencePolicy.TurnEvidence(
            turn.getTurnNo(), turn.getSourceCardId(), turn.getPhase(),
            turn.getQuestionType(), turn.getStatus())).toList());
    var unassessed = new ArrayList<FixedReportInput.UnassessedQuestion>();
    if (session.isRecruitment()) {
      if (storedCards.size() != session.getTotalMainQuestionCount()) throw new IllegalStateException("Frozen recruitment deck is incomplete");
      for (var card : storedCards) {
        var main = storedTurns.stream().filter(t -> t.getSourceCardId().equals(card.getId()) && t.getQuestionType() != interview.pilot.interview.domain.QuestionType.FOLLOW_UP).findFirst();
        if (main.isEmpty() || main.get().getStatus() != interview.pilot.interview.domain.TurnStatus.COMPLETED)
          unassessed.add(new FixedReportInput.UnassessedQuestion(card.getQuestionText(),
              main.isPresent() && main.get().getStatus() == interview.pilot.interview.domain.TurnStatus.FAILED ? "PROCESSING_FAILED" : "NOT_ANSWERED"));
      }
      storedTurns = storedTurns.stream().filter(t -> t.getStatus() == interview.pilot.interview.domain.TurnStatus.COMPLETED).toList();
      if (storedTurns.stream().noneMatch(t -> t.getPhase() != InterviewPhase.SELF_INTRODUCTION))
        throw new IllegalStateException("No completed formal answers available for recruitment evaluation");
    }
    List<EvalStatus> formalStatuses = storedTurns.stream()
        .filter(turn -> turn.getPhase() != InterviewPhase.SELF_INTRODUCTION)
        .map(turn -> turn.getEvalStatus() == null ? EvalStatus.NOT_REQUIRED : turn.getEvalStatus())
        .toList();
    boolean waiting = evaluationBarrier.shouldAwait(formalStatuses, completedRetries,
        evaluationProperties.getReportBarrierMaxAttempts());
    var evidence = new ArrayList<FixedReportInput.TurnEvidence>();
    var allowed = new HashSet<String>();
    var availability = new EnumMap<InterviewPhase, String>(InterviewPhase.class);
    for (var turn : storedTurns) {
      var card = storedCards.stream().filter(value -> value.getId().equals(turn.getSourceCardId()))
          .findFirst().orElseThrow();
      RagContextSnapshot rag = decode(card.getRagContextSnapshot(), RagContextSnapshot.class);
      List<String> refs = decodeStringList(card.getSourceIds());
      rag.requireCurrentSources(refs);
      allowed.addAll(refs);
      if (turn.getPhase() != InterviewPhase.SELF_INTRODUCTION) {
        availability.put(turn.getPhase(), rag.status().name());
      }
      evidence.add(new FixedReportInput.TurnEvidence(
          turn.getTurnNo(), turn.getPhase().name(), turn.getQuestionType().name(),
          turn.getQuestionText(), turn.getAnswerText(), rag, refs, evaluationOf(turn)));
    }
    return new Begin(
        task.getTaskId(), session.getId(), session.getUserAccountId(), session.getSessionId(),
        session.getProviderId(), session.getModelName(), task.getAttemptCount(),
        task.getExecutionEpoch(), new FixedReportInput(brief, evidence, session.isRecruitment(), session.getTotalMainQuestionCount(), unassessed),
        Set.copyOf(allowed), Map.copyOf(availability), token, waiting, null);
  }

  static void validate(
      FixedInterviewReport report,
      Set<String> allowedSources,
      Map<InterviewPhase, String> expectedAvailability, FixedReportInput input) {
    if (report == null) throw new IllegalArgumentException("report is required");
    for (var reference : report.technicalReferences()) {
      if (!allowedSources.contains(reference.sourceId())) {
        throw new IllegalArgumentException("report contains an unknown RAG source");
      }
    }
    if (!report.ragAvailability().equals(expectedAvailability)) {
      throw new InvalidReport("RAG_AVAILABILITY");
    }
    Set<InterviewPhase> expectedPhases = input.recruitment()
        ? input.completedTurns().stream().map(t -> InterviewPhase.valueOf(t.phase()))
            .filter(phase -> phase != InterviewPhase.SELF_INTRODUCTION).collect(java.util.stream.Collectors.toSet())
        : Set.of(
        InterviewPhase.SELF_INTRODUCTION,
        InterviewPhase.FUNDAMENTALS,
        InterviewPhase.PROJECT_EXPERIENCE,
        InterviewPhase.SCENARIO_TRADEOFF);
    if (!report.phaseScores().keySet().equals(expectedPhases)) {
      throw new InvalidReport("PHASES");
    }
  }

  private Outcome complete(TaskMessage message, Begin begin, FixedInterviewReport report) {
    AsyncTaskEntity task = lockedTask(message);
    InterviewSessionEntity session = lockedSession(task);
    if (!current(task, session, begin)) return Outcome.STALE;
    if (reports.findBySessionId(session.getId()).isPresent()) return Outcome.STALE;
    reports.saveAndFlush(InterviewReportEntity.create(
        session.getId(), report.overallScore(), report.summary(), encode(report)));
    session.completeEvaluation();
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.clearExecutionLease();
    task.setLastError(null);
    return Outcome.TERMINAL;
  }

  private Outcome failInvalid(TaskMessage message, Begin begin, String category) {
    AsyncTaskEntity task = lockedTask(message);
    InterviewSessionEntity session = lockedSession(task);
    if (!current(task, session, begin)) return Outcome.STALE;
    task.setStatus(AsyncTaskStatus.FAILED);
    task.clearExecutionLease();
    task.setLastError("INVALID_INTERVIEW_REPORT:" + category);
    session.evaluationFailed("INVALID_INTERVIEW_REPORT:" + category);
    return Outcome.TERMINAL;
  }

  private static final class InvalidReport extends IllegalArgumentException {
    private final String category;
    private InvalidReport(String category) {super("Invalid report: " + category);this.category=category;}
  }

  private boolean recordRetryable(TaskMessage message, Begin begin) {
    AsyncTaskEntity task = lockedTask(message);
    InterviewSessionEntity session = lockedSession(task);
    if (current(task, session, begin)) {
      task.setLastError("INTERVIEW_REPORT_TEMPORARILY_UNAVAILABLE");
      return true;
    }
    return false;
  }

  private boolean current(AsyncTaskEntity task, InterviewSessionEntity session, Begin begin) {
    return task.ownsExecution(begin.executionEpoch(), begin.attemptGeneration(), begin.executionToken())
        && task.getTaskId().equals(begin.taskId())
        && task.getAttemptCount() == begin.attemptGeneration()
        && task.getStatus() == AsyncTaskStatus.PUBLISHED
        && session.getId().equals(begin.sessionDatabaseId())
        && session.getUserAccountId().equals(begin.userAccountId())
        && session.getStatus() == SessionStatus.EVALUATING;
  }

  public void releaseForRetry(TaskMessage message, int generation, String token) {
    transactions.executeWithoutResult(status -> tasks.releaseExecution(message.taskId().toString(),
        AsyncTaskType.INTERVIEW_EVALUATION.name(), message.bizKey(), message.executionEpoch(), generation, token));
  }

  private AsyncTaskEntity lockedTask(TaskMessage message) {
    tasks.findByTaskIdForUpdate(message.taskId()).orElseThrow();
    return task(message);
  }

  private InterviewSessionEntity lockedSession(AsyncTaskEntity task) {
    return sessions.findByIdForUpdate(session(task).getId()).orElseThrow();
  }

  private AsyncTaskEntity task(TaskMessage message) {
    if (message == null || message.taskId() == null
        || message.taskType() != AsyncTaskType.INTERVIEW_EVALUATION) {
      throw new IllegalArgumentException("Interview report message is invalid");
    }
    var task = tasks.findByTaskId(message.taskId()).orElseThrow();
    if (task.getTaskType() != message.taskType()
        || !task.getBizKey().equals(message.bizKey())) {
      throw new IllegalArgumentException("Interview report task identity does not match");
    }
    return task;
  }

  private InterviewSessionEntity session(AsyncTaskEntity task) {
    if (!task.getBizKey().startsWith("interview:")) throw new IllegalArgumentException();
    UUID id = UUID.fromString(task.getBizKey().substring("interview:".length()));
    return sessions.findBySessionIdAndUserAccountId(id, task.getUserAccountId()).orElseThrow();
  }

  private <T> T decode(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored report input is invalid", exception);
    }
  }

  private AnswerEvaluation evaluationOf(InterviewTurnEntity turn) {
    if ((turn.getEvalStatus() == EvalStatus.OK
        || turn.getEvalStatus() == EvalStatus.GENERAL_FALLBACK)
        && turn.getAnswerEvaluation() != null && !turn.getAnswerEvaluation().isBlank()) {
      return decode(turn.getAnswerEvaluation(), AnswerEvaluation.class);
    }
    return null;
  }

  private List<String> decodeStringList(String json) {
    try {
      return objectMapper.readValue(
          json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored report source IDs are invalid", exception);
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Report could not be stored", exception);
    }
  }

  public enum Outcome { TERMINAL, STALE, BUSY }
  public record Target(UUID sessionId, boolean terminal, int attemptGeneration) { }
  private record Begin(
      UUID taskId, Long sessionDatabaseId, Long userAccountId, UUID sessionId,
      String providerId, String modelName, int attemptGeneration, int executionEpoch,
      FixedReportInput input, Set<String> allowedSourceIds,
      Map<InterviewPhase, String> ragAvailability, String executionToken, boolean waiting, Outcome skipped) {
    static Begin skipped(Outcome outcome) {
      return new Begin(null, null, null, null, null, null, 0, 0, null, Set.of(), Map.of(), null, false, outcome);
    }
  }
}
