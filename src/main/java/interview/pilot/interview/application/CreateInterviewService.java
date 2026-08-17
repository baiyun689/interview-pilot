package interview.pilot.interview.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.rag.RagContextSnapshot;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.interview.skill.InterviewSkillCatalog;
import interview.pilot.interview.skill.SkillGroup;
import interview.pilot.knowledge.retrieval.KnowledgeRetriever;
import interview.pilot.knowledge.retrieval.KnowledgeScopeResolver;
import interview.pilot.knowledge.retrieval.RetrievalIntent;
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
  private final InterviewPlanner planner;
  private final QuestionGenerator questions;
  private final InterviewCreationStore store;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final InterviewSkillCatalog skills;
  private final KnowledgeScopeResolver scopeResolver;
  private final KnowledgeRetriever retriever;
  private final InterviewStrategy strategy = new DefaultInterviewStrategy();

  public CreateInterviewService(
      ResumeRepository resumes,
      AiProviderService providers,
      JobProfileExtractor extractor,
      InterviewPlanner planner,
      QuestionGenerator questions,
      InterviewCreationStore store,
      ObjectMapper objectMapper,
      Validator validator,
      InterviewSkillCatalog skills,
      KnowledgeScopeResolver scopeResolver,
      KnowledgeRetriever retriever) {
    this.resumes = resumes;
    this.providers = providers;
    this.extractor = extractor;
    this.planner = planner;
    this.questions = questions;
    this.store = store;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.skills = skills;
    this.scopeResolver = scopeResolver;
    this.retriever = retriever;
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
    InterviewPlan plan = planner.plan(
        providerId, profile, requirements, request.difficulty(), request.totalTurnBudget(),
        skill.snapshot());
    if (plan == null) {
      log.warn("createInterview failed: AI planner returned null plan for skill={}", request.skillId());
      throw invalidAiOutput();
    }
    if (plan.totalTurnBudget() != request.totalTurnBudget()) {
      log.warn("createInterview failed: AI planner budget mismatch expected={} actual={}",
          request.totalTurnBudget(), plan.totalTurnBudget());
      throw invalidAiOutput();
    }
    if (!containsAllCompetencies(plan.competencies(), requirements.competencies())) {
      log.warn("createInterview failed: AI planner competencies {} don't cover required {}",
          plan.competencies(), requirements.competencies());
      throw invalidAiOutput();
    }
    log.info("createInterview plan competencies={} budget={}", plan.competencies(), plan.totalTurnBudget());

    RagContextSnapshot firstRagSnapshot = RagContextSnapshot.notConfigured();
    ValidatedKnowledgeScope scope = null;
    TurnDirective firstDirective = strategy.firstTurn(plan, request.difficulty());

    if (!request.knowledgeBaseIds().isEmpty()) {
      scope = scopeResolver.resolveForCreation(user, request.knowledgeBaseIds());
      var intent = new RetrievalIntent(
          buildQuery(plan, profile, requirements, request.difficulty()),
          firstDirective.competency(), request.difficulty().name(),
          profile.technicalSkills(), List.of(), 5, 0.5);
      try {
        var result = retriever.retrieve(scope, intent);
        log.info("createInterview rag query=\"{}\" status={} chunks={} scores={}",
            intent.query(), result.status(), result.chunks().size(),
            result.chunks().stream().map(c -> String.format("%.2f", c.score())).toList());
        firstRagSnapshot = new RagContextSnapshot(
            convertStatus(result.status()), result.query(), result.embeddingModel(),
            result.chunks().stream().map(c -> new RagContextSnapshot.Chunk(
                c.pointId(), c.documentId(), c.filename(),
                c.chunkIndex(), c.score(), c.content())).toList(),
            result.failureReason());
      } catch (RuntimeException exception) {
        firstRagSnapshot = new RagContextSnapshot(
            RagStatus.UNAVAILABLE, "", "", List.of(), exception.getMessage());
      }
    }

    GeneratedQuestion first = questions.firstQuestion(
        providerId, plan, profile, requirements, skill.snapshot(), firstRagSnapshot,
        firstDirective);
    if (first == null) {
      log.warn("createInterview failed: AI question generator returned null skill={}", request.skillId());
      throw invalidAiOutput();
    }
    if (!firstDirective.competency().equalsIgnoreCase(first.targetCompetency())) {
      log.warn("createInterview failed: AI first question competency '{}' did not match directive '{}'",
          first.targetCompetency(), firstDirective.competency());
      throw invalidAiOutput();
    }
    log.info("createInterview success firstQuestion competency={}", first.targetCompetency());

    return store.create(new InterviewCreation(
        ownerId, resumeId, title, jdText, request.difficulty(), request.totalTurnBudget(),
        providerId, provider.model(), skill.snapshot(), requirements, plan, first,
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

  private boolean containsAllCompetencies(
      java.util.List<String> allowed, java.util.List<String> required) {
    return required.stream().allMatch(item -> allowed.stream()
        .anyMatch(candidate -> candidate.equalsIgnoreCase(item)));
  }

  private static Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private String buildQuery(InterviewPlan plan, ResumeProfile profile,
      Object requirements, Difficulty difficulty) {
    var sb = new StringBuilder();
    sb.append(String.join(" ", plan.competencies()));
    if (profile.technicalSkills() != null && !profile.technicalSkills().isEmpty()) {
      sb.append(' ').append(String.join(" ", profile.technicalSkills().subList(
          0, Math.min(5, profile.technicalSkills().size()))));
    }
    sb.append(' ').append(difficulty.name().toLowerCase(java.util.Locale.ROOT));
    return sb.toString();
  }

  private static RagStatus convertStatus(
      interview.pilot.knowledge.retrieval.RetrievalStatus status) {
    return switch (status) {
      case RETRIEVED -> RagStatus.RETRIEVED;
      case NO_MATCH -> RagStatus.NO_MATCH;
      case UNAVAILABLE -> RagStatus.UNAVAILABLE;
    };
  }

}
