package interview.pilot.interview.application;

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
  }

  public Outcome handle(TaskMessage message, int completedRetries) {
    Begin begin = transactions.execute(status -> begin(message, completedRetries));
    if (begin.terminal()) return Outcome.STALE;
    try {
      FixedInterviewReport report = generator.generate(
          begin.providerId(), begin.modelName(), begin.input());
      validate(report, begin.allowedSourceIds(), begin.ragAvailability(), begin.input());
      return transactions.execute(status -> complete(message, begin, report));
    } catch (AiStructuredOutputException | IllegalArgumentException exception) {
      String category = exception instanceof InvalidReport invalid ? invalid.category
          : exception instanceof AiStructuredOutputException ? "STRUCTURE" : "CONTENT";
      transactions.executeWithoutResult(status -> failInvalid(message, begin, category));
      return Outcome.TERMINAL;
    } catch (RuntimeException exception) {
      transactions.executeWithoutResult(status -> recordRetryable(message, begin));
      throw new ReportGenerationRetryableException(begin.attemptGeneration());
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

  public boolean markDead(TaskMessage message, int attemptGeneration) {
    Boolean result = transactions.execute(status -> {
      AsyncTaskEntity task = task(message);
      InterviewSessionEntity session = session(task);
      if (task.getExecutionEpoch() != message.executionEpoch()
          || task.getAttemptCount() != attemptGeneration
          || session.getStatus() != SessionStatus.EVALUATING) return false;
      task.setStatus(AsyncTaskStatus.DEAD);
      task.setLastError("INTERVIEW_REPORT_RETRY_EXHAUSTED");
      session.evaluationFailed("INTERVIEW_REPORT_RETRY_EXHAUSTED");
      return true;
    });
    return Boolean.TRUE.equals(result);
  }

  private Begin begin(TaskMessage message, int completedRetries) {
    AsyncTaskEntity task = task(message);
    InterviewSessionEntity session = session(task);
    if (task.getExecutionEpoch() != message.executionEpoch()
        || task.getStatus() == AsyncTaskStatus.COMPLETED
        || session.getStatus() == SessionStatus.COMPLETED) {
      return Begin.stale();
    }
    if (session.getStatus() != SessionStatus.EVALUATING
        || reports.findBySessionId(session.getId()).isPresent()) {
      throw new IllegalStateException("Interview report state is inconsistent");
    }
    task.setStatus(AsyncTaskStatus.PUBLISHED);
    task.setAttemptCount(task.getAttemptCount() + 1);
    task.setLastError(null);

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
    if (evaluationBarrier.shouldAwait(formalStatuses, completedRetries,
        evaluationProperties.getReportBarrierMaxAttempts())) {
      // Per-turn evaluations are still running; requeue through the report retry ladder and wait,
      // bounded by reportBarrierMaxAttempts so the report can never hang indefinitely.
      throw new ReportGenerationRetryableException(task.getAttemptCount());
    }
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
        Set.copyOf(allowed), Map.copyOf(availability), false);
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
    AsyncTaskEntity task = task(message);
    InterviewSessionEntity session = session(task);
    if (!current(task, session, begin)) return Outcome.STALE;
    if (reports.findBySessionId(session.getId()).isPresent()) return Outcome.STALE;
    reports.saveAndFlush(InterviewReportEntity.create(
        session.getId(), report.overallScore(), report.summary(), encode(report)));
    session.completeEvaluation();
    task.setStatus(AsyncTaskStatus.COMPLETED);
    task.setLastError(null);
    return Outcome.TERMINAL;
  }

  private void failInvalid(TaskMessage message, Begin begin, String category) {
    AsyncTaskEntity task = task(message);
    InterviewSessionEntity session = session(task);
    if (!current(task, session, begin)) return;
    task.setStatus(AsyncTaskStatus.FAILED);
    task.setLastError("INVALID_INTERVIEW_REPORT:" + category);
    session.evaluationFailed("INVALID_INTERVIEW_REPORT:" + category);
  }

  private static final class InvalidReport extends IllegalArgumentException {
    private final String category;
    private InvalidReport(String category) {super("Invalid report: " + category);this.category=category;}
  }

  private void recordRetryable(TaskMessage message, Begin begin) {
    AsyncTaskEntity task = task(message);
    InterviewSessionEntity session = session(task);
    if (current(task, session, begin)) {
      task.setLastError("INTERVIEW_REPORT_TEMPORARILY_UNAVAILABLE");
    }
  }

  private boolean current(AsyncTaskEntity task, InterviewSessionEntity session, Begin begin) {
    return task.getExecutionEpoch() == begin.executionEpoch()
        && task.getTaskId().equals(begin.taskId())
        && task.getAttemptCount() == begin.attemptGeneration()
        && task.getStatus() == AsyncTaskStatus.PUBLISHED
        && session.getId().equals(begin.sessionDatabaseId())
        && session.getUserAccountId().equals(begin.userAccountId())
        && session.getStatus() == SessionStatus.EVALUATING;
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

  public enum Outcome { TERMINAL, STALE }
  public record Target(UUID sessionId, boolean terminal, int attemptGeneration) { }
  private record Begin(
      UUID taskId, Long sessionDatabaseId, Long userAccountId, UUID sessionId,
      String providerId, String modelName, int attemptGeneration, int executionEpoch,
      FixedReportInput input, Set<String> allowedSourceIds,
      Map<InterviewPhase, String> ragAvailability, boolean terminal) {
    static Begin stale() {
      return new Begin(null, null, null, null, null, null, 0, 0, null, Set.of(), Map.of(), true);
    }
  }
}
