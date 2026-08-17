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
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewDecision;
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
import interview.pilot.interview.grounding.GroundingDirective;
import interview.pilot.interview.grounding.KnowledgeGrounding;
import interview.pilot.interview.grounding.GroundingUsePolicy;
import interview.pilot.interview.skill.SkillSnapshot;
import interview.pilot.knowledge.retrieval.KnowledgeScopeResolver;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.interview.strategy.DefaultInterviewStrategy;
import interview.pilot.interview.strategy.InterviewStrategy;
import interview.pilot.interview.strategy.TurnDirective;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.common.observability.AiMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SubmitAnswerService {
  private static final double MINIMUM_DECISION_CONFIDENCE = 0.55;
  private static final long POLL_INTERVAL_MILLIS = 100;
  private static final long MAX_POLL_INTERVAL_MILLIS = 1_000;
  private static final String SAFE_PROCESSING_ERROR = "AI_PROCESSING_FAILED";

  private final InterviewTurnClaimer claimer;
  private final AnswerEvaluator evaluator;
  private final QuestionGenerator questionGenerator;
  private final InterviewStrategy strategy = new DefaultInterviewStrategy();
  private final AnswerEvidenceValidator evidenceValidator = new AnswerEvidenceValidator();
  private final AnswerGroundingValidator answerGrounding = new AnswerGroundingValidator();
  private final QuestionGroundingValidator questionGrounding = new QuestionGroundingValidator();
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
  private final KnowledgeGrounding grounding;
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
      KnowledgeGrounding grounding,
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
    this.grounding = grounding;
    this.requiresNew = new TransactionTemplate(transactionManager);
    this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.metrics = metrics;
  }

  /** All model calls and duplicate waits happen outside database transactions. */
  public AnswerProcessingResult submit(CurrentUser user, UUID sessionId, SubmitAnswerRequest request) {
    InterviewTurnClaim claim = claim(user, sessionId, request);
    return processClaim(user, sessionId, request, claim);
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
      String sid = shortId(sessionId);
      WorkContext context = requiresNew.execute(status -> loadContext(user, sessionId, claim));
      log.info("interview session={} turn={} step=start competency={} difficulty={}",
          sid, claim.turnNo(), context.currentCompetency(), context.currentDifficulty());
      // Use the current turn's RAG snapshot for evaluation
      RagContextSnapshot currentRag = readRagSnapshot(claim);
      if (currentRag.status() == RagStatus.RETRIEVED) {
        log.info("interview session={} turn={} step=eval_rag chunks={} scores={}",
            sid, claim.turnNo(), currentRag.chunks().size(),
            currentRag.chunks().stream().map(c -> String.format("%.2f", c.score())).toList());
      }

      long evalStart = System.nanoTime();
      AnswerEvaluation evaluation = evaluator.evaluate(new AnswerEvaluationRequest(
          context.providerId(), context.modelName(), claim.turnNo(), context.currentDifficulty(),
          context.currentCompetency(), context.question(), context.answer(),
          context.requirements().competencies(), context.plan().competencies(),
          context.priorEvidence(), context.skill(),
          GroundingUsePolicy.allowsFactVerification(context.currentDirective())
              ? currentRag : currentRag.hiddenForDisallowedUse(),
          context.currentDirective()));
      long evalMs = (System.nanoTime() - evalStart) / 1_000_000;
      if (evaluation == null) {
        throw new IllegalStateException("Answer evaluator returned no result");
      }
      evaluation = evidenceValidator.validate(request.answer(), evaluation);
      evaluation = answerGrounding.validate(evaluation, currentRag, context.currentDirective());
      log.info("interview session={} turn={} step=evaluate provider={} model={} score={} latency_ms={}",
          sid, claim.turnNo(), context.providerId(), context.modelName(),
          evaluation.score(), evalMs);

      DecisionContext decisionContext = decisionContexts.create(
          context.currentDifficulty(), context.currentCompetency(),
          context.plan().competencies(), claim.turnNo(), context.plan().totalTurnBudget(),
          MINIMUM_DECISION_CONFIDENCE, context.completedTurns(), evaluation);
      var strategyOutcome = strategy.nextTurn(context.plan(), decisionContext, evaluation);
      InterviewDecision decision = strategyOutcome.decision();
      TurnDirective nextDirective = strategyOutcome.nextDirective();
      Difficulty nextDifficulty = nextDirective == null
          ? context.currentDifficulty() : nextDirective.difficulty();
      boolean finish = decision.nextStep() == NextStep.FINISH
          || claim.turnNo() >= context.plan().totalTurnBudget();
      log.info("interview session={} turn={} step=decision nextStep={} targetCompetency={} nextDifficulty={} confidence={}",
          sid, claim.turnNo(), decision.nextStep(), decision.targetCompetency(),
          nextDifficulty, decision.confidence());

      // Retrieve next RAG snapshot after Java decision
      RagContextSnapshot nextRag = finish
          ? RagContextSnapshot.notConfigured()
          : retrieveNextGrounding(user, sessionId, claim.turnNo(), context, nextDirective);

      long genStart = System.nanoTime();
      GeneratedQuestion nextQuestion = finish ? null : questionGenerator.nextQuestion(
          context.providerId(), context.modelName(),
          new QuestionContext(
              context.plan(), context.resume(), context.requirements(), nextDifficulty,
              context.question(), context.answer(), context.skill()).withRag(
                  GroundingUsePolicy.allowsQuestionGeneration(nextDirective)
                      ? nextRag : nextRag.hiddenForDisallowedUse()),
          decision, nextDirective);
      if (!finish) nextQuestion = questionGrounding.validate(
          nextQuestion, nextRag, nextDirective);
      if (!finish) nextRag = nextRag.withQuestion(nextQuestion);
      if (!finish) {
        metrics.interviewGrounding(
            context.skill().id(), nextDirective.competency(), nextRag.status().name(),
            nextRag.chunks().stream().mapToInt(chunk -> chunk.content().length()).sum(),
            nextQuestion.evidenceRefs().size());
      }
      long genMs = (System.nanoTime() - genStart) / 1_000_000;
      validateNextQuestion(nextQuestion, decision, context.plan(), finish);
      if (!finish) {
        log.info("interview session={} turn={} step=generate_question provider={} model={} competency={} latency_ms={}",
            sid, claim.turnNo(), context.providerId(), context.modelName(),
            nextQuestion.targetCompetency(), genMs);
      } else {
        log.info("interview session={} turn={} step=finish totalTurns={}",
            sid, claim.turnNo(), claim.turnNo());
      }
      AnswerProcessingResult result = new AnswerProcessingResult(
          sessionId, request.requestId(), claim.turnNo(), evaluation, decision, nextQuestion,
          nextDifficulty, finish ? SessionStatus.EVALUATING : SessionStatus.INTERVIEWING, false,
          nextRag, finish ? null : nextDirective, context.currentDirective());
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
    ResumeProfile profile;
    if (session.getResumeId() != null) {
      var resume = resumes.findById(session.getResumeId())
          .orElseThrow(() -> new IllegalStateException("Interview resume is missing"));
      profile = read(resume.getSkillsSnapshot(), ResumeProfile.class);
      if (!validator.validate(profile).isEmpty()) {
        throw new IllegalStateException("Stored resume profile is invalid");
      }
    } else {
      profile = ResumeProfile.empty();
    }
    InterviewPlan plan = read(session.getPlanSnapshot(), InterviewPlan.class);
    JobRequirements requirements = read(job.getRequirementsSnapshot(), JobRequirements.class);
    SkillSnapshot skill = read(job.getSkillSnapshot(), SkillSnapshot.class);
    if (!containsAllCompetencies(plan.competencies(), requirements.competencies())) {
      throw new IllegalStateException("Stored interview target set is invalid");
    }
    List<InterviewTurnEntity> allTurns = turns.findAllBySessionIdOrderByTurnNo(session.getId());
    TurnDirective currentDirective = readDirective(
        session.getContextSnapshot(), plan, current.getTargetCompetency(), current.getDifficulty());
    return new WorkContext(
        session.getProviderId(), session.getModelName(), session.getDifficulty(),
        current.getTargetCompetency(), current.getQuestionText(), current.getAnswerText(),
        plan, requirements, profile, skill, currentDirective,
        historicalEvidence(allTurns, publicSessionId),
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
      if (result.nextDirective() != null) {
        session.setContextSnapshot(objectMapper.writeValueAsString(result.nextDirective()));
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

  private boolean sameCompetency(String left, String right) {
    return key(left).equals(key(right));
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

  private TurnDirective readDirective(
      String snapshot, InterviewPlan plan, String competency, Difficulty difficulty) {
    if (snapshot != null && !snapshot.isBlank()) {
      try {
        TurnDirective directive = objectMapper.readValue(snapshot, TurnDirective.class);
        if (directive != null && sameCompetency(directive.competency(), competency)) {
          return directive;
        }
      } catch (JacksonException | IllegalArgumentException ignored) {
        // Legacy sessions derive their directive from the immutable plan below.
      }
    }
    var item = plan.itemFor(competency);
    return new TurnDirective(
        item.stageId(), item.competency(), difficulty, item.evidenceTargets(),
        item.questionModes().getFirst(), item.ragEnabled(), "", "LEGACY_PLAN_DERIVED", "",
        item.retrievalPolicy());
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

  private RagContextSnapshot retrieveNextGrounding(
      CurrentUser user, UUID sessionId, int currentTurnNo, WorkContext context,
      TurnDirective nextDirective) {
    try {
      var session = sessions.findBySessionIdAndUserAccountId(sessionId, requireOwner(user))
          .orElse(null);
      if (session == null || session.getKnowledgeScopeSnapshot() == null) {
        return grounding.ground(null,
            GroundingDirective.from(nextDirective)).toRagContext();
      }
      var scope = objectMapper.readValue(
          session.getKnowledgeScopeSnapshot(), ValidatedKnowledgeScope.class);
      long ragStart = System.nanoTime();
      var result = grounding.ground(
          scope, GroundingDirective.from(nextDirective));
      long ragMs = (System.nanoTime() - ragStart) / 1_000_000;
      log.info("interview session={} turn={} step=grounding status={} chunks={} scores={} latency_ms={}",
          shortId(sessionId), currentTurnNo, result.status(),
          result.chunks().size(),
          result.chunks().stream().map(c -> String.format("%.2f", c.score())).toList(),
          ragMs);
      return result.toRagContext();
    } catch (JacksonException exception) {
      return new interview.pilot.interview.grounding.GroundingSnapshot(
          interview.pilot.interview.grounding.GroundingStatus.UNAVAILABLE,
          "", "", List.of(), "INVALID_SCOPE_SNAPSHOT").toRagContext();
    } catch (RuntimeException exception) {
      return new interview.pilot.interview.grounding.GroundingSnapshot(
          interview.pilot.interview.grounding.GroundingStatus.UNAVAILABLE,
          "", "", List.of(), exception.getMessage()).toRagContext();
    }
  }

  private static String shortId(UUID id) {
    String s = id.toString();
    return s.substring(0, 8);
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
      TurnDirective currentDirective,
      List<String> priorEvidence,
      List<InterviewDecisionContextFactory.CompletedTurnEvidence> completedTurns) {

  }
}
