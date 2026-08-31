package interview.pilot.interview.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.common.observability.AiMetrics;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.domain.QuestionDeck;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.grounding.GroundingDirective;
import interview.pilot.interview.grounding.KnowledgeGrounding;
import interview.pilot.interview.grounding.GroundingUsePolicy;
import interview.pilot.interview.skill.InterviewSkillCatalog;
import interview.pilot.interview.skill.SkillGroup;
import interview.pilot.interview.preset.ClasspathInterviewPresetCatalog;
import interview.pilot.interview.preset.InterviewPresetCatalog;
import interview.pilot.knowledge.retrieval.KnowledgeScopeResolver;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.interview.strategy.DefaultInterviewStrategy;
import interview.pilot.interview.strategy.InterviewStrategy;
import interview.pilot.interview.strategy.TurnDirective;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CreateInterviewService {
  private final ResumeRepository resumes;
  private final AiProviderService providers;
  private final JobProfileExtractor extractor;
  private final InterviewPlanCompiler planCompiler;
  private final FixedInterviewPlanCompiler fixedPlanCompiler;
  private final QuestionGenerator questions;
  private final InterviewCreationStore store;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final InterviewSkillCatalog skills;
  private final KnowledgeScopeResolver scopeResolver;
  private final KnowledgeGrounding grounding;
  private final AiMetrics metrics;
  private final InterviewPresetCatalog presets;
  private final boolean fixedFlow;
  private final InterviewStrategy strategy = new DefaultInterviewStrategy();
  private final QuestionGroundingValidator questionGrounding = new QuestionGroundingValidator();

  @Autowired
  public CreateInterviewService(
      ResumeRepository resumes,
      AiProviderService providers,
      JobProfileExtractor extractor,
      InterviewPlanCompiler planCompiler,
      QuestionGenerator questions,
      InterviewCreationStore store,
      ObjectMapper objectMapper,
      Validator validator,
      InterviewSkillCatalog skills,
      KnowledgeScopeResolver scopeResolver,
      KnowledgeGrounding grounding,
      AiMetrics metrics,
      InterviewPresetCatalog presets) {
    this(resumes, providers, extractor, planCompiler, new FixedInterviewPlanCompiler(), questions,
        store, objectMapper, validator, skills, scopeResolver, grounding, metrics, presets, true);
  }

  private CreateInterviewService(
      ResumeRepository resumes,
      AiProviderService providers,
      JobProfileExtractor extractor,
      InterviewPlanCompiler planCompiler,
      FixedInterviewPlanCompiler fixedPlanCompiler,
      QuestionGenerator questions,
      InterviewCreationStore store,
      ObjectMapper objectMapper,
      Validator validator,
      InterviewSkillCatalog skills,
      KnowledgeScopeResolver scopeResolver,
      KnowledgeGrounding grounding,
      AiMetrics metrics,
      InterviewPresetCatalog presets,
      boolean fixedFlow) {
    this.resumes = resumes;
    this.providers = providers;
    this.extractor = extractor;
    this.planCompiler = planCompiler;
    this.fixedPlanCompiler = fixedPlanCompiler;
    this.questions = questions;
    this.store = store;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.skills = skills;
    this.scopeResolver = scopeResolver;
    this.grounding = grounding;
    this.metrics = metrics;
    this.presets = presets;
    this.fixedFlow = fixedFlow;
  }

  public CreateInterviewService(
      ResumeRepository resumes,
      AiProviderService providers,
      JobProfileExtractor extractor,
      InterviewPlanCompiler planCompiler,
      QuestionGenerator questions,
      InterviewCreationStore store,
      ObjectMapper objectMapper,
      Validator validator,
      InterviewSkillCatalog skills,
      KnowledgeScopeResolver scopeResolver,
      KnowledgeGrounding grounding,
      AiMetrics metrics) {
    this(resumes, providers, extractor, planCompiler, new FixedInterviewPlanCompiler(), questions,
        store, objectMapper, validator, skills, scopeResolver, grounding, metrics,
        new ClasspathInterviewPresetCatalog(), false);
  }

  /** Orchestrates remote calls without opening a database transaction. */
  public InterviewSessionResponse create(CurrentUser user, CreateInterviewRequest request) {
    Long ownerId = requireOwner(user);
    ResumeProfile profile;
    Long resumeId;
    if (request.resumeId() != null) {
      ResumeEntity resume = resumes.findByIdAndUserAccountId(request.resumeId(), ownerId)
          .orElseThrow(() -> new BusinessException(
              "RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));
      if (resume.getStatus() != ResumeStatus.READY) {
        throw new BusinessException(
            "RESUME_NOT_READY", "Resume analysis is not ready", HttpStatus.CONFLICT);
      }
      profile = readProfile(resume.getSkillsSnapshot());
      resumeId = resume.getId();
    } else {
      profile = ResumeProfile.empty();
      resumeId = null;
    }
    String title = normalize(request.jobTitle());
    String jdText = normalize(request.jdText());
    if (!"custom".equals(request.presetId()) && jdText.isBlank()) {
      var preset = presets.require(request.presetId());
      jdText = preset.jobDescription();
      if (title.isBlank()) title = preset.displayName();
    }
    var skill = skills.require(request.skillId());
    if (title.isBlank() && skill.group() != SkillGroup.CUSTOM) title = skill.name();
    if (skill.group() == SkillGroup.CUSTOM && title.isBlank()) {
      throw new BusinessException(
          "CUSTOM_JOB_TITLE_REQUIRED",
          "自定义岗位必须填写岗位名称",
          HttpStatus.BAD_REQUEST);
    }
    if (skill.group() == SkillGroup.CUSTOM && jdText.isBlank()) {
      throw new BusinessException(
          "CUSTOM_JOB_DESCRIPTION_REQUIRED",
          "自定义岗位必须填写岗位描述",
          HttpStatus.BAD_REQUEST);
    }

    AiProviderDescriptor provider = providers.resolveEnabled(request.providerId());
    String providerId = provider.id();
    log.info("createInterview provider={} model={} skill={} difficulty={} turns={} hasResume={} kbCount={}",
        providerId, provider.model(), request.skillId(), request.difficulty(),
        request.totalTurnBudget(), resumeId != null, request.knowledgeBaseIds().size());

    var requirements = extractor.extract(providerId, jdText, skill.snapshot());
    if (requirements == null) {
      log.warn("createInterview failed: AI job-profile extractor returned null for skill={}", request.skillId());
      throw invalidAiOutput();
    }
    InterviewPlan plan = fixedFlow
        ? fixedPlanCompiler.compile(profile, requirements, request.difficulty(),
            request.totalTurnBudget())
        : planCompiler.compile(profile, requirements, request.difficulty(),
            request.totalTurnBudget(), skill.snapshot());
    log.info("createInterview fixedFlow={} phases={} budget={}", fixedFlow,
        plan.competencies(), plan.totalTurnBudget());

    ValidatedKnowledgeScope scope = null;
    TurnDirective firstDirective = strategy.firstTurn(plan, request.difficulty());

    if (!request.knowledgeBaseIds().isEmpty()) {
      scope = scopeResolver.resolveForCreation(user, request.knowledgeBaseIds());
    }
    var groundingSnapshot = grounding.ground(
        scope, fixedFlow
            ? GroundingDirective.forQuestionDeck(request.difficulty(), plan)
            : GroundingDirective.from(firstDirective));
    RagContextSnapshot firstRagSnapshot = groundingSnapshot.toRagContext();
    log.info("createInterview grounding status={} chunks={} scores={}",
        groundingSnapshot.status(), groundingSnapshot.chunks().size(),
        groundingSnapshot.chunks().stream().map(c -> String.format("%.2f", c.score())).toList());

    GeneratedQuestion first = fixedFlow
        ? new GeneratedQuestion(
            "请先做一个简短的自我介绍，重点说明与你应聘的 Java 后端岗位最相关的经历。",
            "自我介绍")
        : questions.firstQuestion(
            providerId, plan, profile, requirements, skill.snapshot(),
            GroundingUsePolicy.allowsQuestionGeneration(firstDirective)
                ? firstRagSnapshot : firstRagSnapshot.hiddenForDisallowedUse(),
            firstDirective);
    if (first == null) {
      log.warn("createInterview failed: AI question generator returned null skill={}", request.skillId());
      throw invalidAiOutput();
    }
    first = questionGrounding.validate(first, firstRagSnapshot, firstDirective);
    firstRagSnapshot = firstRagSnapshot.withQuestion(first);
    metrics.interviewGrounding(
        skill.id(), firstDirective.competency(), firstRagSnapshot.status().name(),
        firstRagSnapshot.chunks().stream().mapToInt(chunk -> chunk.content().length()).sum(),
        first.evidenceRefs().size());
    if (!firstDirective.competency().equalsIgnoreCase(first.targetCompetency())) {
      log.warn("createInterview failed: AI first question competency '{}' did not match directive '{}'",
          first.targetCompetency(), firstDirective.competency());
      throw invalidAiOutput();
    }
    log.info("createInterview success firstQuestion competency={}", first.targetCompetency());

    QuestionDeck questionDeck;
    try {
      questionDeck = questions.generatePrimaryQuestions(
          providerId, plan, profile, requirements, skill.snapshot(), firstRagSnapshot, firstDirective)
          .withFirst(first);
      log.info("createInterview primaryQuestionDeck size={}", questionDeck.questions().size());
    } catch (RuntimeException exception) {
      // The first question is already valid. Keep creation available when the optional
      // batch call is unavailable; later turns retain the existing online fallback.
      log.warn("createInterview primary question deck unavailable; using first question only", exception);
      questionDeck = QuestionDeck.of(first);
    }

    return store.create(new InterviewCreation(
        ownerId, resumeId, title, jdText, request.difficulty(), request.totalTurnBudget(),
        providerId, provider.model(), skill.snapshot(), requirements, plan, questionDeck, first,
        scope, firstRagSnapshot, firstDirective));
  }

  private BusinessException invalidAiOutput() {
    return new BusinessException(
        "INVALID_AI_OUTPUT", "AI output did not match the interview plan", HttpStatus.BAD_GATEWAY);
  }

  private ResumeProfile readProfile(String snapshot) {
    if (snapshot == null || snapshot.isBlank()) {
      throw invalidStoredProfile();
    }
    try {
      ResumeProfile profile = objectMapper.readValue(snapshot, ResumeProfile.class);
      if (profile == null || !validator.validate(profile).isEmpty()) {
        throw invalidStoredProfile();
      }
      return profile;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw invalidStoredProfile();
    }
  }

  private BusinessException invalidStoredProfile() {
    return new BusinessException(
        "RESUME_PROFILE_INVALID", "Stored resume profile is invalid", HttpStatus.CONFLICT);
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

}
