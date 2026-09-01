package interview.pilot.voice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.config.VoiceProperties.Asr;
import interview.pilot.voice.config.VoiceProperties.Tts;
import interview.pilot.voice.domain.QuestionSpeechStatus;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;

class QuestionSpeechTaskCreatorTest {
  private static final Asr ASR = new Asr(
      "dashscope", "https://dashscope.aliyuncs.com/api/v1", "sk-test",
      "fun-asr-flash-2026-06-15", Duration.ofSeconds(60));
  private static final Tts TTS = new Tts(
      "dashscope", "cosyvoice-v3-flash", "longanyang", Duration.ofSeconds(30));

  private final QuestionSpeechRepository speeches = mock(QuestionSpeechRepository.class);
  private final AsyncTaskRepository tasks = mock(AsyncTaskRepository.class);

  private QuestionSpeechTaskCreator creator(VoiceProperties properties) {
    return new QuestionSpeechTaskCreator(speeches, tasks, properties);
  }

  private static VoiceProperties voiceWithTts(Tts tts) {
    return new VoiceProperties(true, Path.of("build/voice"), 8_388_608,
        Duration.ofMinutes(5), Duration.ofDays(7), ASR, tts);
  }

  @Test
  void voiceSessionWithTtsConfiguredCreatesOneSpeechRowAndOneTaskRow() {
    var session = session(InterviewMode.VOICE);
    var turn = turn(7L, "请自我介绍");
    when(speeches.findByTurnId(7L)).thenReturn(Optional.empty());
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        any(AsyncTaskType.class), any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());

    UUID speechId = creator(voiceWithTts(TTS)).createForTurn(session, turn);

    assertThat(speechId).isNotNull();
    ArgumentCaptor<QuestionSpeechEntity> speechCaptor =
        ArgumentCaptor.forClass(QuestionSpeechEntity.class);
    verify(speeches).saveAndFlush(speechCaptor.capture());
    QuestionSpeechEntity speech = speechCaptor.getValue();
    assertThat(speech.getStatus()).isEqualTo(QuestionSpeechStatus.PENDING);
    assertThat(speech.getSpeechId()).isEqualTo(speechId);
    assertThat(speech.getTextSha256()).isEqualTo(QuestionSpeechHashes.of("请自我介绍"));
    assertThat(speech.getProviderId()).isEqualTo("dashscope");
    assertThat(speech.getModelName()).isEqualTo("cosyvoice-v3-flash");
    assertThat(speech.getVoiceName()).isEqualTo("longanyang");
    assertThat(speech.getUserAccountId()).isEqualTo(3L);
    assertThat(speech.getTurnId()).isEqualTo(7L);
    ArgumentCaptor<AsyncTaskEntity> taskCaptor =
        ArgumentCaptor.forClass(AsyncTaskEntity.class);
    verify(tasks).saveAndFlush(taskCaptor.capture());
    AsyncTaskEntity task = taskCaptor.getValue();
    assertThat(task.getTaskType()).isEqualTo(AsyncTaskType.QUESTION_SPEECH_SYNTHESIS);
    assertThat(task.getBizKey()).isEqualTo(
        QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId);
    assertThat(task.getPayloadSnapshot()).contains("\"speechId\":\"" + speechId + "\"");
  }

  @Test
  void textSessionCreatesNoRowsOrTasks() {
    var session = session(InterviewMode.TEXT);
    when(speeches.findByTurnId(7L)).thenReturn(Optional.empty());

    UUID speechId = creator(voiceWithTts(TTS)).createForTurn(session, turn(7L, "请自我介绍"));

    assertThat(speechId).isNull();
    verifyNoInteractions(speeches, tasks);
  }

  @Test
  void unconfiguredTtsCreatesNoRowsOrTasksEvenForVoiceSessions() {
    var session = session(InterviewMode.VOICE);
    // TTS is degradable: absent tts config means no rows and no tasks (capabilities expose it).
    UUID speechId = creator(voiceWithTts(null)).createForTurn(session, turn(7L, "请自我介绍"));

    assertThat(speechId).isNull();
    verifyNoInteractions(speeches, tasks);
  }

  @Test
  void repeatedExecutionReturnsTheExistingRowWithoutDuplicates() {
    var session = session(InterviewMode.VOICE);
    var turn = turn(7L, "请自我介绍");
    UUID existingSpeechId = UUID.randomUUID();
    when(speeches.findByTurnId(7L)).thenReturn(
        Optional.of(QuestionSpeechEntity.pending(3L, existingSpeechId, 9L, 7L,
            QuestionSpeechHashes.of("请自我介绍"), "dashscope", "cosyvoice-v3-flash", "longanyang")));

    UUID speechId = creator(voiceWithTts(TTS)).createForTurn(session, turn);

    assertThat(speechId).isEqualTo(existingSpeechId);
    verify(speeches, never()).saveAndFlush(any());
    verify(tasks, never()).saveAndFlush(any());
  }

  @Test
  void existingTaskRowIsReusedWhenTheSpeechRowIsFresh() {
    // Mirror of VoiceAnswerServiceImpl.createTranscriptionTask: the task row is unique per
    // speech id; a row left by a previous execution is reused, never duplicated.
    var session = session(InterviewMode.VOICE);
    var turn = turn(7L, "请自我介绍");
    when(speeches.findByTurnId(7L)).thenReturn(Optional.empty());
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        any(AsyncTaskType.class), any(String.class), any(Long.class)))
        .thenAnswer(invocation -> {
          String bizKey = invocation.getArgument(1);
          if (bizKey.startsWith(QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX)) {
            return Optional.of(AsyncTaskEntity.pending(
                3L, AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, bizKey, "{}"));
          }
          return Optional.empty();
        });

    UUID created = creator(voiceWithTts(TTS)).createForTurn(session, turn);

    assertThat(created).isNotNull();
    verify(speeches).saveAndFlush(any());
    verify(tasks, never()).saveAndFlush(any());
  }

  @Test
  void aLostInsertRaceReturnsTheWinnersCommittedSpeechRow() {
    // uq_question_speech_turn backstop: the flush forces the duplicate-key violation inside
    // the creator, and the re-read finds the winner's committed row.
    var session = session(InterviewMode.VOICE);
    var turn = turn(7L, "请自我介绍");
    UUID winnerSpeechId = UUID.randomUUID();
    when(speeches.findByTurnId(7L)).thenReturn(Optional.empty(),
        Optional.of(QuestionSpeechEntity.pending(3L, winnerSpeechId, 9L, 7L,
            QuestionSpeechHashes.of("请自我介绍"), "dashscope", "cosyvoice-v3-flash", "longanyang")));
    when(speeches.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup turn"));
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        any(AsyncTaskType.class), any(String.class), any(Long.class)))
        .thenReturn(Optional.of(AsyncTaskEntity.pending(
            3L, AsyncTaskType.QUESTION_SPEECH_SYNTHESIS,
            QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + winnerSpeechId, "{}")));

    UUID speechId = creator(voiceWithTts(TTS)).createForTurn(session, turn);

    assertThat(speechId).isEqualTo(winnerSpeechId);
  }

  @Test
  void aLostTaskInsertRaceIsSwallowedByTheUniqueBizKeyBackstop() {
    var session = session(InterviewMode.VOICE);
    var turn = turn(7L, "请自我介绍");
    when(speeches.findByTurnId(7L)).thenReturn(Optional.empty());
    when(tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        any(AsyncTaskType.class), any(String.class), any(Long.class)))
        .thenReturn(Optional.empty());
    when(tasks.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup biz key"));

    UUID speechId = creator(voiceWithTts(TTS)).createForTurn(session, turn);

    assertThat(speechId).isNotNull();
    verify(speeches).saveAndFlush(any());
  }

  private static InterviewSessionEntity session(InterviewMode mode) {
    var session = mock(InterviewSessionEntity.class);
    when(session.getInterviewMode()).thenReturn(mode);
    when(session.getUserAccountId()).thenReturn(3L);
    return session;
  }

  private static InterviewTurnEntity turn(Long id, String questionText) {
    var turn = mock(InterviewTurnEntity.class);
    when(turn.getId()).thenReturn(id);
    when(turn.getSessionId()).thenReturn(9L);
    when(turn.getQuestionText()).thenReturn(questionText);
    return turn;
  }
}
