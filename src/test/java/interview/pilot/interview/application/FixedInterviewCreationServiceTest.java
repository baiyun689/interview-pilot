package interview.pilot.interview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import interview.pilot.ai.provider.AiProviderDescriptor;
import interview.pilot.ai.provider.AiProviderService;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.common.exception.BusinessException;
import interview.pilot.interview.api.CreateInterviewRequest;
import interview.pilot.interview.api.CreateInterviewRequest.JobSource;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.preset.InterviewPresetCatalog;
import interview.pilot.knowledge.retrieval.KnowledgeScopeResolver;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.domain.VoiceSnapshot;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;

class FixedInterviewCreationServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ResumeRepository resumes;
  private AiProviderService providers;
  private InterviewPresetCatalog presets;
  private KnowledgeScopeResolver scopes;
  private InterviewSessionRepository sessions;
  private AsyncTaskRepository tasks;
  private Validator validator;

  private CurrentUser user;
  private InterviewSessionEntity savedSession;

  @BeforeEach
  void setUp() {
    resumes = mock(ResumeRepository.class);
    providers = mock(AiProviderService.class);
    presets = mock(InterviewPresetCatalog.class);
    scopes = mock(KnowledgeScopeResolver.class);
    sessions = mock(InterviewSessionRepository.class);
    tasks = mock(AsyncTaskRepository.class);
    validator = mock(Validator.class);
    user = new CurrentUser(3L, UUID.randomUUID(), "user@example.com", "User");
    when(providers.resolveEnabled("dashscope")).thenReturn(
        new AiProviderDescriptor("dashscope", "DashScope", "qwen-plus", true, true));
    when(sessions.save(any())).thenAnswer(call -> call.getArgument(0));
    when(tasks.save(any())).thenAnswer(call -> call.getArgument(0));
  }

  @Test
  void textCreationPersistsSessionWithoutVoiceSnapshot() {
    var service = service(voice(false, false));

    service.create(user, request(InterviewMode.TEXT));

    var session = captureSavedSession();
    assertThat(session.getInterviewMode()).isEqualTo(InterviewMode.TEXT);
    assertThat(session.getVoiceSnapshot()).isNull();
  }

  @Test
  void textCreationTreatsNullModeAsText() {
    var service = service(voice(false, false));

    service.create(user, request(null));

    assertThat(captureSavedSession().getInterviewMode()).isEqualTo(InterviewMode.TEXT);
  }

  @Test
  void voiceCreationPersistsSnapshotBuiltFromConfiguration() {
    var service = service(voice(true, true));

    service.create(user, request(InterviewMode.VOICE));

    var session = captureSavedSession();
    assertThat(session.getInterviewMode()).isEqualTo(InterviewMode.VOICE);
    VoiceSnapshot snapshot = decode(session.getVoiceSnapshot());
    assertThat(snapshot.schemaVersion()).isEqualTo(1);
    assertThat(snapshot.asrProvider()).isEqualTo("dashscope");
    assertThat(snapshot.asrModel()).isEqualTo("fun-asr-flash-2026-06-15");
    assertThat(snapshot.ttsProvider()).isEqualTo("dashscope");
    assertThat(snapshot.ttsModel()).isEqualTo("cosyvoice-v3-flash");
    assertThat(snapshot.voice()).isEqualTo("longanyang");
    assertThat(snapshot.maxRecordingSeconds()).isEqualTo(300);
    assertThat(snapshot.maxUploadBytes()).isEqualTo(8_388_608);
  }

  @Test
  void voiceCreationPersistsServerDefaultVoiceWhenTtsIsUnconfigured() {
    var service = service(voice(true, false));

    service.create(user, request(InterviewMode.VOICE));

    VoiceSnapshot snapshot = decode(captureSavedSession().getVoiceSnapshot());
    assertThat(snapshot.ttsProvider()).isEqualTo("unconfigured");
    assertThat(snapshot.voice()).isEqualTo("server-default");
  }

  @Test
  void voiceCreationRejectedWithConflictWhenVoiceIsDisabled() {
    var service = service(voice(false, false));

    assertThatThrownBy(() -> service.create(user, request(InterviewMode.VOICE)))
        .isInstanceOf(BusinessException.class)
        .satisfies(exception -> {
          var business = (BusinessException) exception;
          assertThat(business.code()).isEqualTo("VOICE_MODE_UNAVAILABLE");
          assertThat(business.status()).isEqualTo(HttpStatus.CONFLICT);
        });

    verify(sessions, never()).save(any());
  }

  private FixedInterviewCreationService service(VoiceProperties voice) {
    return new FixedInterviewCreationService(
        resumes, providers, presets, scopes, sessions, tasks, objectMapper, validator, voice);
  }

  private InterviewSessionEntity captureSavedSession() {
    var captor = org.mockito.ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessions).save(captor.capture());
    return captor.getValue();
  }

  private VoiceSnapshot decode(String json) {
    try {
      return objectMapper.readValue(json, VoiceSnapshot.class);
    } catch (Exception exception) {
      throw new AssertionError("stored voice snapshot is not decodable", exception);
    }
  }

  private static CreateInterviewRequest request(InterviewMode mode) {
    return new CreateInterviewRequest(
        null,
        new JobSource(JobSourceType.CUSTOM, null, "Java 后端", "构建后端服务"),
        Difficulty.MEDIUM, InterviewSize.STANDARD, "dashscope", List.of(), mode);
  }

  private static VoiceProperties voice(boolean enabled, boolean ttsConfigured) {
    return new VoiceProperties(
        enabled, Path.of("./data/voice"), 8_388_608, Duration.ofMinutes(5), Duration.ofDays(7),
        new VoiceProperties.Asr(
            "dashscope", "https://dashscope.aliyuncs.com/api/v1", "sk-test",
            "fun-asr-flash-2026-06-15", Duration.ofSeconds(60)),
        ttsConfigured
            ? new VoiceProperties.Tts("dashscope", "cosyvoice-v3-flash", "longanyang", Duration.ofSeconds(30))
            : null);
  }
}
