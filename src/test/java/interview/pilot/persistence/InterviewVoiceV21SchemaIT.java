package interview.pilot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import interview.pilot.auth.infrastructure.UserAccountEntity;
import interview.pilot.auth.infrastructure.UserAccountRepository;
import interview.pilot.common.ratelimit.RateLimiter;
import interview.pilot.interview.domain.Difficulty;
import interview.pilot.interview.domain.GroundingMode;
import interview.pilot.interview.domain.InputMode;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.domain.InterviewPhase;
import interview.pilot.interview.domain.InterviewSize;
import interview.pilot.interview.domain.JobSourceType;
import interview.pilot.interview.domain.QuestionType;
import interview.pilot.interview.infrastructure.InterviewQuestionCardEntity;
import interview.pilot.interview.infrastructure.InterviewQuestionCardRepository;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.interview.rag.RagStatus;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.domain.VoiceRecordingStatus;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;
import interview.pilot.voice.infrastructure.VoiceRecordingEntity;
import interview.pilot.voice.infrastructure.VoiceRecordingRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
    "app.async.rabbit.dispatch-initial-delay=1h"
})
@Testcontainers
class InterviewVoiceV21SchemaIT {
  @Container static final MySQLContainer MYSQL = new MySQLContainer(
      DockerImageName.parse("mysql:8.4")).withDatabaseName("interview_pilot_v21_schema");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean RedissonClient redissonClient;
  @MockitoBean RateLimiter rateLimiter;

  @Autowired UserAccountRepository users;
  @Autowired InterviewSessionRepository sessions;
  @Autowired InterviewQuestionCardRepository cards;
  @Autowired InterviewTurnRepository turns;
  @Autowired VoiceRecordingRepository recordings;
  @Autowired QuestionSpeechRepository speeches;

  @Test
  void sessionModeAndVoiceSnapshotPersistAndNewTurnsDefaultToText() {
    var owner = users.save(UserAccountEntity.register("v21-schema@example.com", "!", "V21"));
    var session = sessions.saveAndFlush(InterviewSessionEntity.preparing(
        owner.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null));
    var card = cards.saveAndFlush(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.FUNDAMENTALS, 1, "并发", "请解释并发问题",
        "[]", GroundingMode.GENERAL, RagStatus.NOT_REQUESTED, "{}", "[]", 1,
        "如果超时你会怎么办？"));

    var turn = turns.saveAndFlush(InterviewTurnEntity.asked(
        session.getId(), 1, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN,
        card.getId(), "请解释并发问题"));

    assertThat(turn.getInputMode()).isEqualTo(InputMode.TEXT);
    assertThat(sessions.findBySessionId(session.getSessionId()).orElseThrow()
        .getInterviewMode()).isEqualTo(InterviewMode.TEXT);

    var voiceSession = sessions.saveAndFlush(InterviewSessionEntity.preparing(
        owner.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null,
        InterviewMode.VOICE, "{\"schemaVersion\":1}"));
    String storedSnapshot = sessions.findBySessionId(voiceSession.getSessionId())
        .orElseThrow().getVoiceSnapshot();
    assertThat(storedSnapshot).contains("\"schemaVersion\"");
    assertThat(new tools.jackson.databind.ObjectMapper()
        .readTree(storedSnapshot).get("schemaVersion").asInt()).isEqualTo(1);
  }

  @Test
  void voiceRecordingLifecyclePersistsAndRejectsIllegalTransitions() {
    var owner = users.save(UserAccountEntity.register("v21-rec@example.com", "!", "V21"));
    var session = sessions.saveAndFlush(InterviewSessionEntity.preparing(
        owner.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null));
    var card = cards.saveAndFlush(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.FUNDAMENTALS, 1, "并发", "请解释并发问题",
        "[]", GroundingMode.GENERAL, RagStatus.NOT_REQUESTED, "{}", "[]", 1, null));
    var turn = turns.saveAndFlush(InterviewTurnEntity.asked(
        session.getId(), 1, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN,
        card.getId(), "请解释并发问题"));

    UUID recordingId = UUID.randomUUID();
    UUID uploadRequestId = UUID.randomUUID();
    var recording = recordings.saveAndFlush(VoiceRecordingEntity.receiving(
        owner.getId(), recordingId, uploadRequestId, session.getId(), turn.getId(),
        Instant.now().plusSeconds(3600)));
    assertThat(recording.getStatus()).isEqualTo(VoiceRecordingStatus.RECEIVING);

    recording.moveTo(VoiceRecordingStatus.UPLOADED);
    recordings.saveAndFlush(recording);
    assertThat(recordings.findByRecordingId(recordingId).orElseThrow().getStatus())
        .isEqualTo(VoiceRecordingStatus.UPLOADED);
    assertThat(recordings.findByUploadRequestId(uploadRequestId)).isPresent();
    assertThat(recordings.findAllBySessionIdAndTurnIdOrderByCreatedAt(
        session.getId(), turn.getId())).hasSize(1);

    var doomed = VoiceRecordingEntity.receiving(
        owner.getId(), UUID.randomUUID(), UUID.randomUUID(), session.getId(), turn.getId(),
        Instant.now().plusSeconds(3600));
    assertThatThrownBy(() -> doomed.moveTo(VoiceRecordingStatus.ATTACHED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void questionSpeechLifecyclePersistsPerTurnAndRejectsIllegalTransitions() {
    var owner = users.save(UserAccountEntity.register("v21-tts@example.com", "!", "V21"));
    var session = sessions.saveAndFlush(InterviewSessionEntity.preparing(
        owner.getId(), null, Difficulty.MEDIUM, InterviewSize.STANDARD,
        JobSourceType.CUSTOM, "Java 后端", "dashscope", "qwen", "{}", null));
    var card = cards.saveAndFlush(InterviewQuestionCardEntity.create(
        session.getId(), InterviewPhase.FUNDAMENTALS, 1, "并发", "请解释并发问题",
        "[]", GroundingMode.GENERAL, RagStatus.NOT_REQUESTED, "{}", "[]", 1, null));
    var turn = turns.saveAndFlush(InterviewTurnEntity.asked(
        session.getId(), 1, InterviewPhase.FUNDAMENTALS, QuestionType.MAIN,
        card.getId(), "请解释并发问题"));

    UUID speechId = UUID.randomUUID();
    var speech = speeches.saveAndFlush(QuestionSpeechEntity.pending(
        owner.getId(), speechId, session.getId(), turn.getId(),
        "a".repeat(64), "dashscope", "cosyvoice-v3-flash", "longanyang"));
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);

    speech.moveTo(QuestionSpeechStatus.SYNTHESIZING);
    speech.moveTo(QuestionSpeechStatus.READY);
    speeches.saveAndFlush(speech);
    assertThat(speeches.findBySpeechId(speechId).orElseThrow().getStatus())
        .isEqualTo(QuestionSpeechStatus.READY);
    assertThat(speeches.findByTurnId(turn.getId()).orElseThrow().getTextSha256())
        .isEqualTo("a".repeat(64));

    var doomed = QuestionSpeechEntity.pending(
        owner.getId(), UUID.randomUUID(), session.getId(), turn.getId(),
        "b".repeat(64), "dashscope", "cosyvoice-v3-flash", "longanyang");
    assertThatThrownBy(() -> doomed.moveTo(QuestionSpeechStatus.READY))
        .isInstanceOf(IllegalStateException.class);
  }
}
