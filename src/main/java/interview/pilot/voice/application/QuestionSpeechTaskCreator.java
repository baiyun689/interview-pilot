package interview.pilot.voice.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import interview.pilot.async.domain.AsyncTaskType;
import interview.pilot.async.infrastructure.AsyncTaskEntity;
import interview.pilot.async.infrastructure.AsyncTaskRepository;
import interview.pilot.async.policy.QuestionSpeechSynthesisRetryPolicy;
import interview.pilot.interview.domain.InterviewMode;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.voice.config.VoiceProperties;
import interview.pilot.voice.infrastructure.QuestionSpeechEntity;
import interview.pilot.voice.infrastructure.QuestionSpeechRepository;

/**
 * Creates the question_speech row and its unique QUESTION_SPEECH_SYNTHESIS task row for a
 * turn (plan §11). Both services that create turns ({@code StartInterviewService},
 * {@code FixedAnswerService}) call this INSIDE their turn-creation transaction, so a rollback
 * removes the speech row with the turn.
 *
 * <p>Gate: only VOICE sessions with TTS configured get rows (TTS is a degradable playback
 * capability — unconfigured means no rows and no tasks, and the frontend reads
 * {@code capabilities.ttsEnabled}). The text_sha256 column pins the turn's question text at
 * creation; the listener re-hashes the immutable text to guard against drift.
 *
 * <p>Uniqueness: {@code uq_question_speech_turn} (V21) guarantees one speech row per turn.
 * A repeated execution (e.g. {@code FixedAnswerService.completeInTransaction} re-run after an
 * optimistic-lock rollback, or a replayed request) returns the existing row instead of
 * failing — the same getOrCreate pattern as
 * {@code VoiceAnswerServiceImpl.createTranscriptionTask}. The task bizKey format
 * {@code question-speech:{speechId}} is owned by
 * {@link QuestionSpeechSynthesisRetryPolicy#BIZ_KEY_PREFIX} — never a literal.
 */
@Service
public class QuestionSpeechTaskCreator {

  private final QuestionSpeechRepository speeches;
  private final AsyncTaskRepository tasks;
  private final VoiceProperties properties;

  public QuestionSpeechTaskCreator(
      QuestionSpeechRepository speeches,
      AsyncTaskRepository tasks,
      VoiceProperties properties) {
    this.speeches = speeches;
    this.tasks = tasks;
    this.properties = properties;
  }

  /** Must be invoked inside the caller's transaction. Returns null for non-voice or unconfigured sessions. */
  public UUID createForTurn(InterviewSessionEntity session, InterviewTurnEntity turn) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(turn, "turn");
    if (session.getInterviewMode() != InterviewMode.VOICE || !properties.ttsConfigured()) {
      return null;
    }
    var existing = speeches.findByTurnId(turn.getId());
    if (existing.isPresent()) {
      return existing.get().getSpeechId(); // repeated execution: return the existing row
    }
    UUID speechId = UUID.randomUUID();
    speeches.save(QuestionSpeechEntity.pending(
        session.getUserAccountId(), speechId, turn.getSessionId(), turn.getId(),
        QuestionSpeechHashes.of(turn.getQuestionText()),
        properties.tts().provider(), properties.tts().model(), properties.tts().voice()));
    createTask(session.getUserAccountId(), speechId);
    return speechId;
  }

  private void createTask(Long userAccountId, UUID speechId) {
    String bizKey = QuestionSpeechSynthesisRetryPolicy.BIZ_KEY_PREFIX + speechId;
    var existing = tasks.findByTaskTypeAndBizKeyAndUserAccountId(
        AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, bizKey, userAccountId);
    if (existing.isEmpty()) {
      tasks.save(AsyncTaskEntity.pending(
          userAccountId, AsyncTaskType.QUESTION_SPEECH_SYNTHESIS, bizKey,
          "{\"speechId\":\"" + speechId + "\"}"));
    }
  }
}
