package interview.pilot.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.redisson.api.RedissonClient;

import interview.pilot.ai.provider.AiSettingRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.application.CurrentUser;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.auth.jwt.JwtTokenService;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.voice.application.SpeechRecognizer;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

@SpringBootTest(properties = {
    "VOICE_ENABLED=false",
    "DASHSCOPE_SPEECH_BASE_URL=garbage-base-url",
    "DASHSCOPE_SPEECH_API_KEY=",
    "DASHSCOPE_ASR_MODEL=",
    "DASHSCOPE_TTS_MODEL=",
    "DASHSCOPE_TTS_VOICE=",
    "VOICE_MAX_UPLOAD_BYTES=0",
    "VOICE_MAX_RECORDING_DURATION=0s",
    "VOICE_MEDIA_RETENTION=0s",
    "DASHSCOPE_ENABLED=true",
    "DASHSCOPE_API_KEY=context-test-key",
    "DASHSCOPE_MODEL=qwen-plus",
    "spring.flyway.enabled=false",
    "management.health.rabbit.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@AutoConfigureMockMvc
class VoiceDisabledContextTest {
  @MockitoBean
  private RedissonClient redissonClient;

  @MockitoBean
  private AiSettingRepository aiSettingRepository;

  @MockitoBean
  private ResumeRepository resumeRepository;

  @MockitoBean
  private AsyncTaskRepository asyncTaskRepository;

  @MockitoBean
  private InterviewSessionRepository interviewSessionRepository;

  @MockitoBean
  private InterviewQuestionCardRepository interviewQuestionCardRepository;

  @MockitoBean
  private InterviewTurnRepository interviewTurnRepository;

  @MockitoBean
  private AnswerAttemptRepository answerAttemptRepository;

  @MockitoBean
  private InterviewReportRepository interviewReportRepository;

  @MockitoBean
  private InterviewKnowledgeBaseRepository interviewKnowledgeBaseRepository;

  @MockitoBean
  private PlatformTransactionManager transactionManager;

  @MockitoBean
  private UserAccountRepository userAccountRepository;

  @MockitoBean
  private KnowledgeBaseJpaRepository knowledgeBaseJpaRepository;

  @MockitoBean
  private KnowledgeDocumentJpaRepository knowledgeDocumentJpaRepository;

  @MockitoBean
  private VoiceRecordingRepository voiceRecordingRepository;

  @MockitoBean
  private QuestionSpeechRepository questionSpeechRepository;

  @MockitoBean
  private RateLimiter rateLimiter;

  @Autowired
  private VoiceProperties voiceProperties;

  @Autowired
  private JwtTokenService jwtService;

  @Autowired
  private MockMvc mockMvc;

  @Autowired(required = false)
  private VoiceMediaStore voiceMediaStore;

  @Autowired(required = false)
  private AudioProbe audioProbe;

  @Autowired(required = false)
  private SpeechRecognizer speechRecognizer;

  @BeforeEach
  void allowRateLimits() {
    when(rateLimiter.allowFixedWindow(any(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  @Test
  void bootsWithGarbageVoiceCredentialsWhenVoiceIsDisabled() {
    assertThat(voiceProperties.enabled()).isFalse();
    assertThat(voiceProperties.asrConfigured()).isFalse();
    assertThat(voiceProperties.ttsConfigured()).isFalse();
  }

  @Test
  void doesNotCreateMediaBeansWhenVoiceIsDisabled() {
    assertThat(voiceMediaStore).isNull();
    assertThat(audioProbe).isNull();
    assertThat(speechRecognizer).isNull();
  }

  @Test
  void capabilitiesReportDisabledWithGarbageConfiguration() throws Exception {
    mockMvc.perform(get("/api/voice/capabilities")
            .header("Authorization", bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.supportedMimeTypes").isArray())
        .andExpect(jsonPath("$.maxRecordingSeconds").value(0))
        .andExpect(jsonPath("$.maxUploadBytes").value(0))
        .andExpect(jsonPath("$.ttsEnabled").value(false));
  }

  @Test
  void textInterviewCreationWorksUnconditionallyWhileVoiceIsDisabled() throws Exception {
    when(interviewSessionRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    when(asyncTaskRepository.save(any())).thenAnswer(call -> call.getArgument(0));

    mockMvc.perform(post("/api/interviews")
            .header("Authorization", bearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "jobSource": {
                    "type": "CUSTOM",
                    "jobTitle": "Java 后端",
                    "jobDescription": "构建后端服务"
                  },
                  "difficulty": "MEDIUM",
                  "interviewSize": "STANDARD",
                  "providerId": "dashscope",
                  "interviewMode": "TEXT"
                }
                """))
        .andExpect(status().isAccepted());

    var captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(interviewSessionRepository).save(captor.capture());
    assertThat(captor.getValue().getInterviewMode()).isEqualTo(InterviewMode.TEXT);
    assertThat(captor.getValue().getVoiceSnapshot()).isNull();
  }

  private String bearer() {
    return "Bearer " + jwtService.issueAccessToken(
        new CurrentUser(1L, UUID.randomUUID(), "context@example.com", "Context"));
  }
}
