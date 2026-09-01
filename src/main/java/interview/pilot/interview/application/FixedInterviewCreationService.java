package interview.pilot.interview.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.CreateInterviewResponse;
import interview.pilot.interview.domain.InterviewBriefSnapshot;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.preset.InterviewPresetCatalog;
import interview.pilot.knowledge.retrieval.KnowledgeScopeResolver;
import interview.pilot.knowledge.retrieval.ValidatedKnowledgeScope;
import interview.pilot.resume.domain.ResumeProfile;
import interview.pilot.resume.domain.ResumeStatus;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.voice.config.VoiceProperties;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FixedInterviewCreationService {
  public static final int FLOW_VERSION = 1;

  private final ResumeRepository resumes;
  private final AiProviderService providers;
  private final InterviewPresetCatalog presets;
  private final KnowledgeScopeResolver scopes;
  private final InterviewSessionRepository sessions;
  private final AsyncTaskRepository tasks;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final VoiceProperties voice;

  public FixedInterviewCreationService(
      ResumeRepository resumes,
      AiProviderService providers,
      InterviewPresetCatalog presets,
      KnowledgeScopeResolver scopes,
      InterviewSessionRepository sessions,
      AsyncTaskRepository tasks,
      ObjectMapper objectMapper,
      Validator validator,
      VoiceProperties voice) {
    this.resumes = resumes;
    this.providers = providers;
    this.presets = presets;
    this.scopes = scopes;
    this.sessions = sessions;
    this.tasks = tasks;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.voice = voice;
  }

  @Transactional
  public CreateInterviewResponse create(CurrentUser user, CreateInterviewRequest request) {
    Long ownerId = requireOwner(user);
    if (request.interviewMode() == InterviewMode.VOICE && !voice.asrConfigured()) {
      throw new BusinessException(
          "VOICE_MODE_UNAVAILABLE", "Voice mode is not available", HttpStatus.CONFLICT);
    }
    ResumeProfile resume = ResumeProfile.empty();
    Long resumeId = null;
    if (request.resumeId() != null) {
      var entity = resumes.findByIdAndUserAccountId(request.resumeId(), ownerId)
          .orElseThrow(() -> notFound("RESUME_NOT_FOUND", "Resume not found"));
      if (entity.getStatus() != ResumeStatus.READY) {
        throw conflict("RESUME_NOT_READY", "Resume analysis is not ready");
      }
      resume = decodeResume(entity.getSkillsSnapshot());
      resumeId = entity.getId();
    }

    String presetId = "";
    String presetVersion = "";
    String title;
    String description;
    JobSourceType sourceType = request.jobSource().type();
    if (sourceType == JobSourceType.PRESET) {
      final interview.pilot.interview.preset.InterviewPreset preset;
      try {
        preset = presets.require(request.jobSource().presetId());
      } catch (IllegalArgumentException exception) {
        throw new BusinessException(
            "INTERVIEW_PRESET_NOT_FOUND", "Interview preset not found", HttpStatus.BAD_REQUEST);
      }
      presetId = preset.id();
      presetVersion = preset.version();
      title = preset.jobTitle();
      description = preset.jobDescription();
    } else {
      title = request.jobSource().jobTitle().trim();
      description = request.jobSource().jobDescription().trim();
    }

    AiProviderDescriptor provider = providers.resolveEnabled(request.providerId());
    ValidatedKnowledgeScope scope = request.knowledgeBaseIds().isEmpty()
        ? null : scopes.resolveForCreation(user, request.knowledgeBaseIds());
    var brief = new InterviewBriefSnapshot(
        sourceType, presetId, presetVersion, title, description,
        resumeId, resume, request.difficulty(), request.interviewSize(),
        provider.id(), provider.model(), scope, FLOW_VERSION);
    String briefJson = encode(brief);
    String scopeJson = scope == null ? null : encode(scope);

    InterviewMode mode = request.interviewMode();
    var session = sessions.save(InterviewSessionEntity.preparing(
        ownerId, resumeId, request.difficulty(), request.interviewSize(), sourceType,
        title, provider.id(), provider.model(), briefJson, scopeJson,
        mode, mode == InterviewMode.VOICE ? encode(voice.toSnapshot()) : null));
    sessions.flush();
    var task = tasks.save(AsyncTaskEntity.pending(
        ownerId,
        AsyncTaskType.INTERVIEW_QUESTION_PREPARATION,
        "interview:" + session.getSessionId(),
        encode(java.util.Map.of(
            "sessionId", session.getSessionId(),
            "flowVersion", FLOW_VERSION))));
    tasks.flush();
    return new CreateInterviewResponse(
        session.getSessionId(), session.getStatus(), task.getTaskId());
  }

  private ResumeProfile decodeResume(String json) {
    try {
      ResumeProfile profile = objectMapper.readValue(json, ResumeProfile.class);
      if (profile == null || !validator.validate(profile).isEmpty()) {
        throw new IllegalArgumentException();
      }
      return profile;
    } catch (JacksonException | IllegalArgumentException exception) {
      throw conflict("RESUME_PROFILE_INVALID", "Stored resume profile is invalid");
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Interview snapshot could not be encoded", exception);
    }
  }

  private Long requireOwner(CurrentUser user) {
    if (user == null || user.databaseId() == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return user.databaseId();
  }

  private BusinessException notFound(String code, String message) {
    return new BusinessException(code, message, HttpStatus.NOT_FOUND);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(code, message, HttpStatus.CONFLICT);
  }
}
