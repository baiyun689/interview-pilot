package interview.pilot.interview.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.InterviewSessionResponse;
import interview.pilot.interview.domain.GeneratedQuestion;
import interview.pilot.interview.domain.InterviewPlan;
import interview.pilot.interview.skill.InterviewSkillCatalog;
import interview.pilot.interview.skill.SkillGroup;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeEntity;
import interview.pilot.resume.infrastructure.ResumeRepository;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

  public CreateInterviewService(
      ResumeRepository resumes,
      AiProviderService providers,
      JobProfileExtractor extractor,
      InterviewPlanner planner,
      QuestionGenerator questions,
      InterviewCreationStore store,
      ObjectMapper objectMapper,
      Validator validator,
      InterviewSkillCatalog skills) {
    this.resumes = resumes;
    this.providers = providers;
    this.extractor = extractor;
    this.planner = planner;
    this.questions = questions;
    this.store = store;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.skills = skills;
  }

  /** Orchestrates remote calls without opening a database transaction. */
  public InterviewSessionResponse create(CurrentUser user, CreateInterviewRequest request) {
    Long ownerId = requireOwner(user);
    ResumeEntity resume = resumes.findByIdAndUserAccountId(request.resumeId(), ownerId)
        .orElseThrow(() -> new BusinessException(
            "RESUME_NOT_FOUND", "Resume not found", HttpStatus.NOT_FOUND));
    if (resume.getStatus() != ResumeStatus.READY) {
      throw new BusinessException(
          "RESUME_NOT_READY", "Resume analysis is not ready", HttpStatus.CONFLICT);
    }
    ResumeProfile profile = readProfile(resume.getSkillsSnapshot());
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
    var requirements = extractor.extract(providerId, jdText, skill.snapshot());
    if (requirements == null) {
      throw invalidAiOutput();
    }
    InterviewPlan plan = planner.plan(
        providerId, profile, requirements, request.difficulty(), request.totalTurnBudget(),
        skill.snapshot());
    if (plan == null || plan.totalTurnBudget() != request.totalTurnBudget()) {
      throw invalidAiOutput();
    }
    if (!containsAllCompetencies(plan.competencies(), requirements.competencies())) {
      throw invalidAiOutput();
    }
    GeneratedQuestion first = questions.firstQuestion(
        providerId, plan, profile, requirements, skill.snapshot());
    if (first == null
        || !plan.competencies().stream().anyMatch(first.targetCompetency()::equalsIgnoreCase)) {
      throw invalidAiOutput();
    }

    return store.create(new InterviewCreation(
        ownerId, resume.getId(), title, jdText, request.difficulty(), request.totalTurnBudget(),
        providerId, provider.model(), skill.snapshot(), requirements, plan, first));
  }

  @Deprecated(forRemoval = true)
  public InterviewSessionResponse create(CreateInterviewRequest request) {
    return create(legacyUser(), request);
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

  private static CurrentUser legacyUser() {
    return new CurrentUser(1L, new java.util.UUID(0L, 1L),
        "legacy-demo@invalid.local", "Legacy Demo");
  }
}
