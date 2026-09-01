package interview.pilot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.redisson.api.RedissonClient;

import interview.pilot.ai.provider.AiSettingRepository;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.interview.infrastructure.AnswerAttemptRepository;
import interview.pilot.interview.infrastructure.InterviewKnowledgeBaseRepository;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewReportRepository;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeBaseJpaRepository;
import interview.pilot.knowledge.infrastructure.KnowledgeDocumentJpaRepository;
import interview.pilot.resume.infrastructure.ResumeRepository;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.infrastructure.AudioProbe;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;
import interview.pilot.voice.storage.VoiceMediaStore;

/** Pins nested app.voice.asr.* / app.voice.tts.* placeholder binding end to end. */
@SpringBootTest(properties = {
    "VOICE_ENABLED=true",
    "VOICE_FILES_ROOT=build/voice-test-files",
    "VOICE_MAX_UPLOAD_BYTES=8388608",
    "VOICE_MAX_RECORDING_DURATION=5m",
    "VOICE_MEDIA_RETENTION=7d",
    "DASHSCOPE_SPEECH_BASE_URL=https://dashscope.aliyuncs.com/api/v1",
    "DASHSCOPE_SPEECH_API_KEY=sk-binding-test",
    "DASHSCOPE_WORKSPACE_ID=",
    "DASHSCOPE_ASR_MODEL=fun-asr-flash-2026-06-15",
    "DASHSCOPE_ASR_TIMEOUT=60s",
    "DASHSCOPE_TTS_MODEL=cosyvoice-v3-flash",
    "DASHSCOPE_TTS_VOICE=longanyang",
    "DASHSCOPE_TTS_TIMEOUT=30s",
    "spring.flyway.enabled=false",
    "management.health.rabbit.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
        + "org.redisson.spring.starter.RedissonAutoConfigurationV4"
})
class VoiceEnabledBindingTest {
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

  @Autowired
  private VoiceProperties voiceProperties;

  @Autowired
  private VoiceMediaStore voiceMediaStore;

  @Autowired
  private AudioProbe audioProbe;

  @Test
  void exposesTheMediaStoreAndProbeWhenVoiceIsEnabled() {
    assertThat(voiceMediaStore).isNotNull();
    assertThat(audioProbe).isNotNull();
  }

  @Test
  void bindsNestedVoiceConfigurationWhenEnabled() {
    assertThat(voiceProperties.enabled()).isTrue();
    assertThat(voiceProperties.asrConfigured()).isTrue();
    assertThat(voiceProperties.ttsConfigured()).isTrue();
    assertThat(voiceProperties.maxRecordingSeconds()).isEqualTo(300);
    assertThat(voiceProperties.maxUploadBytes()).isEqualTo(8_388_608);

    var snapshot = voiceProperties.toSnapshot();
    assertThat(snapshot.asrProvider()).isEqualTo("dashscope");
    assertThat(snapshot.asrModel()).isEqualTo("fun-asr-flash-2026-06-15");
    assertThat(snapshot.ttsModel()).isEqualTo("cosyvoice-v3-flash");
    assertThat(snapshot.voice()).isEqualTo("longanyang");
  }
}
