package interview.pilot.interview.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.domain.AnswerEvaluation;
import interview.pilot.interview.domain.AnswerAttemptStatus;
import interview.pilot.interview.domain.DecisionContext;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.DifficultyAdjustment;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
import interview.pilot.interview.domain.InterviewDecisionPolicy;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.JobRequirements;
import interview.pilot.interview.domain.NextStep;
import interview.pilot.interview.domain.QuestionContext;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.domain.TurnStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptEntity;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.JobProfileRepository;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.KnowledgeScopeResolver;
import interview.pilot.knowledge.retrieval.RetrievalIntent;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.common.observability.AiMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

@Service
public class SubmitAnswerService {
  private static final double MINIMUM_DECISION_CONFIDENCE = 0.55;
  private static final long POLL_INTERVAL_MILLIS = 100;
  private static final long MAX_POLL_INTERVAL_MILLIS = 1_000;
  private static final String SAFE_PROCESSING_ERROR = "AI_PROCESSING_FAILED";

  private final InterviewTurnClaimer claimer;
  private final AnswerEvaluator evaluator;
  private final QuestionGenerator questionGenerator;
  private final InterviewDecisionPolicy decisionPolicy = new InterviewDecisionPolicy();
  private final InterviewDecisionContextFactory decisionContexts =
      new InterviewDecisionContextFactory();
  private final InterviewSessionRepository sessions;
  private final AnswerAttemptRepository attempts;
  private final InterviewTurnRepository turns;
  private final JobProfileRepository jobs;
  private final ResumeRepository resumes;
  private final ObjectMapper objectMapper;
  private final StoredAnswerResultCodec resultCodec;
  private final Validator validator;
  private final InterviewProcessingSla processingSla;
  private final InterviewCompletionService completionService;
  private final KnowledgeScopeResolver scopeResolver;
  private final KnowledgeRetriever retriever;
  private final TransactionTemplate requiresNew;
  private final AiMetrics metrics;

  public SubmitAnswerService(
      InterviewTurnClaimer claimer,
      AnswerEvaluator evaluator,
      QuestionGenerator questionGenerator,
      InterviewSessionRepository sessions,
      AnswerAttemptRepository attempts,
      InterviewTurnRepository turns,
      JobProfileRepository jobs,
      ResumeRepository resumes,
      ObjectMapper objectMapper,
      StoredAnswerResultCodec resultCodec,
      Validator validator,
      InterviewProcessingSla processingSla,
      InterviewCompletionService completionService,
      KnowledgeScopeResolver scopeResolver,
      KnowledgeRetriever retriever,
      PlatformTransactionManager transactionManager,
      AiMetrics metrics) {
    this.claimer = claimer;
    this.evaluator = evaluator;
    this.questionGenerator = questionGenerator;
    this.sessions = sessions;
    this.attempts = attempts;
    this.turns = turns;
    this.jobs = jobs;
    this.resumes = resumes;
    this.objectMapper = objectMapper;
    this.resultCodec = resultCodec;
    this.validator = validator;
    this.processingSla = processingSla;
    this.completionService = completionService;
    this.scopeResolver = scopeResolver;
    this.retriever = retriever;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.metrics = metrics;
  }

  /** All model calls and duplicate waits happen outside database transactions. */
  public AnswerProcessingResult submit(CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    InterviewTurnClaim claim = claim(user, sessionId, request);
    return processClaim(user, sessionId, request, claim);
  }

  @Deprecated(forRemoval = true)
  public AnswerProcessingResult submit(UUID sessionId, SubmitAnswerRequest request) {
    return submit(legacyUser(), sessionId, request);
  }

  public InterviewTurnClaim claim(CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    try {
      InterviewTurnClaim claim = claimer.claim(user, sessionId, request.requestId(), request.answer());
      if (!claim.owner()) metrics.answerDuplicate("replay");
      return claim;
    } catch (BusinessException exception) {
      if ("IDEMPOTENCY_KEY_REUSED".equals(exception.code())
          || "TURN_ALREADY_CLAIMED".equals(exception.code())) {
        metrics.answerDuplicate("conflict");
      }
      throw exception;
    }
  }

  @Deprecated(forRemoval = true)
  public InterviewTurnClaim claim(UUID sessionId, SubmitAnswerRequest request) {
    return claim(legacyUser(), sessionId, request);
  }

  public AnswerProcessingResult processClaim(
      CurrentUser user, UUID sessionId, SubmitAnswerRequest request, InterviewTurnClaim claim) {
    if (claim.state() == InterviewTurnClaim.State.COMPLETED) {
      return resultCodec.readCompleted(
          claim.persistedSnapshot(), sessionId, claim.requestId(), claim.turnNo()).asReplay();
    }
    if (claim.state() == InterviewTurnClaim.State.PROCESSING) {
      return awaitPersistedResult(user, sessionId, request.requestId(), request.answer());
    }
    if (claim.state() == InterviewTurnClaim.State.FAILED) {
      throw retryableFailure();
    }

    try {
      WorkContext context = requiresNew.execute(status -> loadContext(user, sessionId, claim));
      // Use the current turn's RAG snapshot for evaluation
      RagContextSnapshot currentRag = readRagSnapshot(claim);
      AnswerEvaluation evaluation = evaluator.evaluate(new AnswerEvaluationRequest(
          context.providerId(), context.modelName(), claim.turnNo(), context.currentDifficulty(),
          context.currentCompetency(), context.question(), context.answer(),
          context.requirements().competencies(), context.plan().competencies(),
          context.priorEvidence(), context.skill(), currentRag));
      if (evaluation == null) {
        throw new IllegalStateException("Answer evaluator returned no result");
      }
      DecisionContext decisionContext = decisionContexts.create(
          context.currentDifficulty(), context.currentCompetency(),
          context.requirements().competencies(), claim.turnNo(), context.plan().totalTurnBudget(),
          MINIMUM_DECISION_CONFIDENCE, context.completedTurns(), evaluation);
      InterviewDecision decision = decisionPolicy.apply(
          trustedSuggestion(evaluation.suggestedDecision(), context.plan()), decisionContext);
      Difficulty nextDifficulty = adjust(context.currentDifficulty(), decision.difficultyAdjustment());
      boolean finish = decision.nextStep() == NextStep.FINISH
          || claim.turnNo() >= context.plan().totalTurnBudget();
      // Retrieve next RAG snapshot after Java decision
      RagContextSnapshot nextRag = finish
          ? RagContextSnapshot.notConfigured()
          : retrieveNextRag(user, sessionId, context, decision, nextDifficulty);
      GeneratedQuestion nextQuestion = finish ? null : questionGenerator.nextQuestion(
          context.providerId(), context.modelName(),
          new QuestionContext(
              context.plan(), context.resume(), context.requirements(), nextDifficulty,
              context.question(), context.answer(), context.skill()).withRag(nextRag),
          decision);
      validateNextQuestion(nextQuestion, decision, context.plan(), finish);
      AnswerProcessingResult result = new AnswerProcessingResult(
          sessionId, request.requestId(), claim.turnNo(), evaluation, decision, nextQuestion,
          nextDifficulty, finish ? SessionStatus.EVALUATING : SessionStatus.INTERVIEWING, false,
          nextRag);
      String snapshot = resultCodec.write(result);
      Long ownerId = requireOwner(user);
      Boolean finalized = requiresNew.execute(
          status -> finalizeOwner(ownerId, claim, result, snapshot));
      if (!Boolean.TRUE.equals(finalized)) {
        throw conflict("ANSWER_OWNERSHIP_LOST", "The answer claim is no longer current");
      }
      return result;
    } catch (RuntimeException exception) {
      requiresNew.executeWithoutResult(status -> markFailedIfOwner(requireOwner(user), claim));
      if (exception instanceof BusinessException business) {
        throw business;
      }
      throw retryableFailure();
    }
  }

  @Deprecated(forRemoval = true)
  public AnswerProcessingResult processClaim(
      UUID sessionId, SubmitAnswerRequest request, InterviewTurnClaim claim) {
    return processClaim(legacyUser(), sessionId, request, claim);
  }

  private AnswerProcessingResult awaitPersistedResult(
      CurrentUser user, UUID sessionId, UUID requestId, String answer) {
    long deadline = System.nanoTime() + processingSla.processingTimeoutNanos();
    long pollInterval = POLL_INTERVAL_MILLIS;
    while (System.nanoTime() < deadline) {
      InterviewTurnClaim observed = claimer.claim(user, sessionId, requestId, answer);
      if (observed.state() == InterviewTurnClaim.State.COMPLETED) {
        return resultCodec.readCompleted(
            observed.persistedSnapshot(), sessionId, requestId, observed.turnNo()).asReplay();
      }
      if (observed.state() == InterviewTurnClaim.State.FAILED) {
        throw retryableFailure();
      }
      try {
        long remainingMillis = Math.max(1,
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        Thread.sleep(Math.min(pollInterval, remainingMillis));
        pollInterval = Math.min(MAX_POLL_INTERVAL_MILLIS, pollInterval * 2);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw conflict("ANSWER_WAIT_INTERRUPTED", "Answer processing was interrupted; retry shortly");
      }
    }
    throw conflict("ANSWER_STILL_PROCESSING", "The answer is still processing; retry shortly");
  }

  public void failOwner(CurrentUser user, InterviewTurnClaim claim) {
    requiresNew.executeWithoutResult(status -> markFailedIfOwner(requireOwner(user), claim));
  }

  private WorkContext loadContext(CurrentUser user, UUID publicSessionId, InterviewTurnClaim claim) {
    InterviewSessionEntity session = sessions.findBySessionIdAndUserAccountId(
        publicSessionId, requireOwner(user))
        .orElseThrow(() -> new IllegalStateException("Interview session is missing"));
    InterviewTurnEntity current = turns.findById(claim.turnId())
        .orElseThrow(() -> new IllegalStateException("Interview turn is missing"));
    if (!matchesClaimedSession(session, current, claim)) {
      throw conflict("ANSWER_OWNERSHIP_LOST", "The answer claim is no longer current");
    }
    if (current.getStatus() != TurnStatus.PROCESSING
        || !claim.requestId().equals(current.getRequestId())
        || current.getVersion() != claim.ownerVersion()
        || !claim.answerHash().equals(AnswerFingerprint.sha256(current.getAnswerText()))) {
      throw conflict("ANSWER_OWNERSHIP_LOST", "The answer claim is no longer current");
    }
    var job = jobs.findById(session.getJobProfileId())
        .orElseThrow(() -> new IllegalStateException("Interview job profile is missing"));
    var resume = resumes.findById(session.getResumeId())
        .orElseThrow(() -> new IllegalStateException("Interview resume is missing"));
    InterviewPlan plan = read(session.getPlanSnapshot(), InterviewPlan.class);
    JobRequirements requirements = read(job.getRequirementsSnapshot(), JobRequirements.class);
    SkillSnapshot skill = read(job.getSkillSnapshot(), SkillSnapshot.class);
    if (!containsAllCompetencies(plan.competencies(), requirements.competencies())) {
      throw new IllegalStateException("Stored interview target set is invalid");
    }
    ResumeProfile profile = read(resume.getSkillsSnapshot(), ResumeProfile.class);
    if (!validator.validate(profile).isEmpty()) {
      throw new IllegalStateException("Stored resume profile is invalid");
    }
    List<InterviewTurnEntity> allTurns = turns.findAllBySessionIdOrderByTurnNo(session.getId());
    return new WorkContext(
        session.getProviderId(), session.getModelName(), session.getDifficulty(),
        current.getTargetCompetency(), current.getQuestionText(), current.getAnswerText(),
        plan, requirements, profile, skill, historicalEvidence(allTurns, publicSessionId),
        completedTurns(allTurns, publicSessionId));
  }

  private List<String> historicalEvidence(
      List<InterviewTurnEntity> allTurns, UUID publicSessionId) {
    List<String> evidence = new ArrayList<>();
    for (InterviewTurnEntity turn : allTurns) {
      if (turn.getStatus() == TurnStatus.COMPLETED && turn.getEvaluationSnapshot() != null) {
        AnswerProcessingResult result = resultCodec.readCompleted(
            turn.getEvaluationSnapshot(), publicSessionId,
            turn.getRequestId(), turn.getTurnNo());
        evidence.addAll(result.evaluation().evidence());
      }
    }
    return List.copyOf(evidence);
  }

  private List<InterviewDecisionContextFactory.CompletedTurnEvidence> completedTurns(
      List<InterviewTurnEntity> allTurns, UUID publicSessionId) {
    List<InterviewDecisionContextFactory.CompletedTurnEvidence> completed = new ArrayList<>();
    for (InterviewTurnEntity turn : allTurns) {
      if (turn.getStatus() == TurnStatus.COMPLETED && turn.getEvaluationSnapshot() != null) {
        AnswerEvaluation evaluation = resultCodec.readCompleted(
            turn.getEvaluationSnapshot(), publicSessionId,
            turn.getRequestId(), turn.getTurnNo()).evaluation();
        completed.add(new InterviewDecisionContextFactory.CompletedTurnEvidence(
            turn.getTargetCompetency(), evaluation.score(), evaluation.evidence()));
      }
    }
    return List.copyOf(completed);
  }

  private boolean finalizeOwner(
      Long ownerId, InterviewTurnClaim claim, AnswerProcessingResult result, String snapshot) {
    InterviewTurnEntity turn = turns.findById(claim.turnId()).orElse(null);
    AnswerAttemptEntity attempt = attempts.findById(claim.attemptId()).orElse(null);
    InterviewSessionEntity session = sessions.findByIdAndUserAccountId(
        claim.sessionDatabaseId(), ownerId).orElse(null);
    if (turn == null || attempt == null || session == null
        || turn.getStatus() != TurnStatus.PROCESSING
        || !claim.requestId().equals(turn.getRequestId())
        || turn.getVersion() != claim.ownerVersion()
        || attempt.getStatus() != AnswerAttemptStatus.PROCESSING
        || !claim.requestId().equals(attempt.getRequestId())
        || !claim.sessionDatabaseId().equals(attempt.getSessionId())
        || !matchesClaimedSession(session, turn, claim)
        || !claim.turnId().equals(attempt.getTurnId())
        || !claim.answerHash().equals(attempt.getAnswerHash())
        || !claim.answerHash().equals(AnswerFingerprint.sha256(turn.getAnswerText()))
        || !claim.sessionDatabaseId().equals(turn.getSessionId())
        || !claim.sessionDatabaseId().equals(session.getId())
        || attempt.getVersion() != claim.attemptVersion()
        || session.getCurrentTurnNo() != claim.turnNo()) {
      return false;
    }
    turn.setFeedbackText(result.evaluation().feedback());
    turn.setScore(BigDecimal.valueOf(result.evaluation().score()));
    turn.setEvaluationSnapshot(snapshot);
    turn.setProcessingError(null);
    turn.setAnsweredAt(Instant.now());
    turn.setStatus(TurnStatus.COMPLETED);
    turns.save(turn);
    attempt.complete(snapshot);
    attempts.save(attempt);

    if (result.nextQuestion() == null) {
      session.beginEvaluation();
      completionService.ensureReportTask(session);
    } else {
      int nextTurnNo = claim.turnNo() + 1;
      var nextTurn = InterviewTurnEntity.nextAsked(
          session.getId(), nextTurnNo, result.nextDifficulty(),
          result.nextQuestion().question(), result.nextQuestion().targetCompetency());
      if (!result.nextRagSnapshot().equals(RagContextSnapshot.notConfigured())) {
        nextTurn.setRagStatus(result.nextRagSnapshot().status());
        nextTurn.setRagContextSnapshot(
            objectMapper.writeValueAsString(result.nextRagSnapshot()));
      }
      turns.save(nextTurn);
      session.advanceTo(nextTurnNo, result.nextDifficulty());
    }
    sessions.saveAndFlush(session);
    turns.flush();
    attempts.flush();
    return true;
  }

  private void markFailedIfOwner(Long ownerId, InterviewTurnClaim claim) {
    InterviewTurnEntity turn = turns.findById(claim.turnId()).orElse(null);
    AnswerAttemptEntity attempt = attempts.findById(claim.attemptId()).orElse(null);
    InterviewSessionEntity session = sessions.findByIdAndUserAccountId(
        claim.sessionDatabaseId(), ownerId).orElse(null);
    if (turn != null && attempt != null && session != null
        && turn.getStatus() == TurnStatus.PROCESSING
        && claim.requestId().equals(turn.getRequestId())
        && turn.getVersion() == claim.ownerVersion()
        && attempt.getStatus() == AnswerAttemptStatus.PROCESSING
        && claim.requestId().equals(attempt.getRequestId())
        && claim.sessionDatabaseId().equals(attempt.getSessionId())
        && claim.turnId().equals(attempt.getTurnId())
        && matchesClaimedSession(session, turn, claim)
        && claim.answerHash().equals(attempt.getAnswerHash())
        && claim.answerHash().equals(AnswerFingerprint.sha256(turn.getAnswerText()))
        && claim.sessionDatabaseId().equals(turn.getSessionId())
        && attempt.getVersion() == claim.attemptVersion()) {
      turn.setStatus(TurnStatus.FAILED);
      turn.setProcessingError(SAFE_PROCESSING_ERROR);
      turns.save(turn);
      attempt.fail(SAFE_PROCESSING_ERROR);
      attempts.save(attempt);
      turns.flush();
      attempts.flush();
    }
  }

  private void validateNextQuestion(
      GeneratedQuestion question, InterviewDecision decision, InterviewPlan plan, boolean finish) {
    if (finish) return;
    if (question == null
        || !sameCompetency(question.targetCompetency(), decision.targetCompetency())
        || plan.competencies().stream().noneMatch(
            competency -> sameCompetency(competency, question.targetCompetency()))) {
      throw new IllegalStateException("Generated question did not match the validated decision");
    }
  }

  private Difficulty adjust(Difficulty current, DifficultyAdjustment adjustment) {
    return switch (adjustment) {
      case KEEP -> current;
      case INCREASE -> current == Difficulty.EASY ? Difficulty.MEDIUM : Difficulty.HARD;
      case DECREASE -> current == Difficulty.HARD ? Difficulty.MEDIUM : Difficulty.EASY;
    };
  }

  private boolean sameCompetency(String left, String right) {
    return key(left).equals(key(right));
  }

  private InterviewDecision trustedSuggestion(
      InterviewDecision suggestion, InterviewPlan plan) {
    if (suggestion != null && suggestion.nextStep() == NextStep.NEXT_TOPIC
        && plan.competencies().stream().noneMatch(
            allowed -> sameCompetency(allowed, suggestion.targetCompetency()))) {
      return null;
    }
    return suggestion;
  }

  private boolean containsAllCompetencies(List<String> allowed, List<String> required) {
    return required.stream().allMatch(item -> allowed.stream()
        .anyMatch(candidate -> sameCompetency(candidate, item)));
  }

  private String key(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private <T> T read(String snapshot, Class<T> type) {
    try {
      T value = objectMapper.readValue(snapshot, type);
      if (value == null) throw new IllegalStateException("Stored interview snapshot is invalid");
      return value;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw new IllegalStateException("Stored interview snapshot is invalid");
    }
  }

  private BusinessException retryableFailure() {
    return new BusinessException(
        SAFE_PROCESSING_ERROR, "Answer processing failed; submit again with a new requestId",
        HttpStatus.BAD_GATEWAY);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private boolean matchesClaimedSession(
      InterviewSessionEntity session, InterviewTurnEntity turn, InterviewTurnClaim claim) {
    return session != null
        && turn != null
        && claim.sessionDatabaseId().equals(session.getId())
        && claim.sessionDatabaseId().equals(turn.getSessionId());
  }

  private static CurrentUser legacyUser() {
    return new CurrentUser(1L, new UUID(0L, 1L), "legacy-demo@invalid.local", "Legacy Demo");
  }

  private RagContextSnapshot readRagSnapshot(InterviewTurnClaim claim) {
    try {
      var turn = turns.findById(claim.turnId()).orElse(null);
      if (turn == null || turn.getRagContextSnapshot() == null) {
        return RagContextSnapshot.notConfigured();
      }
      return objectMapper.readValue(turn.getRagContextSnapshot(), RagContextSnapshot.class);
    } catch (JacksonException | IllegalArgumentException exception) {
      return RagContextSnapshot.notConfigured();
    }
  }

  private RagContextSnapshot retrieveNextRag(
      CurrentUser user, UUID sessionId, WorkContext context,
      InterviewDecision decision, Difficulty nextDifficulty) {
    try {
      var session = sessions.findBySessionIdAndUserAccountId(sessionId, requireOwner(user))
          .orElse(null);
      if (session == null || session.getKnowledgeScopeSnapshot() == null) {
        return RagContextSnapshot.notConfigured();
      }
      var scope = objectMapper.readValue(
          session.getKnowledgeScopeSnapshot(), ValidatedKnowledgeScope.class);
      var query = decision.targetCompetency() + " "
          + (decision.probeFocus() != null ? decision.probeFocus() + " " : "")
          + nextDifficulty.name().toLowerCase(Locale.ROOT);
      var intent = new RetrievalIntent(
          query, decision.targetCompetency(), nextDifficulty.name(),
          context.plan().competencies(), List.of(), 5, 0.5);
      var result = retriever.retrieve(scope, intent);
      return new RagContextSnapshot(
          convertStatus(result.status()), result.query(), result.embeddingModel(),
          result.chunks().stream().map(c -> new RagContextSnapshot.Chunk(
              c.pointId(), c.documentId(), c.filename(),
              c.chunkIndex(), c.score(), c.content())).toList(),
          result.failureReason());
    } catch (JacksonException exception) {
      return new RagContextSnapshot(
          RagStatus.UNAVAILABLE, "", "", List.of(), "Invalid scope snapshot");
    } catch (RuntimeException exception) {
      return new RagContextSnapshot(
          RagStatus.UNAVAILABLE, "", "", List.of(), exception.getMessage());
    }
  }

  private static RagStatus convertStatus(
      interview.pilot.knowledge.retrieval.RetrievalStatus status) {
    return switch (status) {
      case RETRIEVED -> RagStatus.RETRIEVED;
      case NO_MATCH -> RagStatus.NO_MATCH;
      case UNAVAILABLE -> RagStatus.UNAVAILABLE;
    };
  }

  private record WorkContext(
      String providerId,
      String modelName,
      Difficulty currentDifficulty,
      String currentCompetency,
      String question,
      String answer,
      InterviewPlan plan,
      JobRequirements requirements,
      ResumeProfile resume,
      SkillSnapshot skill,
      List<String> priorEvidence,
      List<InterviewDecisionContextFactory.CompletedTurnEvidence> completedTurns) {

  }
}
