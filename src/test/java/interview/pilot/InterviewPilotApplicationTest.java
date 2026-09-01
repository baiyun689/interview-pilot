package interview.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.StreamingTextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.redisson.api.RedissonClient;

import interview.pilot.ai.provider.AiSettingRepository;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.api.InterviewController;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import org.springframework.aop.support.AopUtils;

@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "management.health.rabbit.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
@AutoConfigureMockMvc
class InterviewPilotApplicationTest {
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

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private InterviewController interviewController;

  @Autowired
  private MockMvc mockMvc;

  @Test
  void contextLoads() {}

  @Test
  void rateLimitedControllersAreActuallyProxiedBySpring() {
    assertThat(AopUtils.isAopProxy(interviewController)).isTrue();
  }

  @Test
  void disabledPreconfiguredProvidersDoNotExposeAnyAutoConfiguredAiModel() {
    assertThat(applicationContext.getBeansOfType(ChatModel.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(EmbeddingModel.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(ImageModel.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(TranscriptionModel.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(TextToSpeechModel.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(StreamingTextToSpeechModel.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(ModerationModel.class)).isEmpty();
  }

  @Test
  void applicationApisRequireCredentialsAfterAuthenticationIsEnabled() throws Exception {
    mockMvc.perform(get("/api/ai/providers"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void healthEndpointRemainsPublic() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk());
  }
}
