package interview.pilot.voice.realtime.orchestrator;

import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import interview.pilot.auth.application.CurrentUser;
import interview.pilot.interview.api.InterviewTurnView;
import interview.pilot.interview.api.SubmitAnswerRequest;
import interview.pilot.interview.application.FixedAnswerClaim;
import interview.pilot.interview.application.FixedAnswerResult;
import interview.pilot.interview.application.FixedAnswerService;
import interview.pilot.interview.domain.InputMode;
import interview.pilot.interview.domain.SessionStatus;
import interview.pilot.interview.infrastructure.InterviewSessionEntity;
import interview.pilot.interview.infrastructure.InterviewSessionRepository;
import interview.pilot.interview.infrastructure.InterviewTurnEntity;
import interview.pilot.interview.infrastructure.InterviewTurnRepository;
import interview.pilot.voice.realtime.audio.PcmWavEncoder;
import interview.pilot.voice.realtime.tts.RealtimeTtsClient;

/**
 * Bridges the realtime voice transport onto the existing fixed-answer turn engine.
 *
 * <p>This is the deliberate reuse point: unlike the reference project's standalone agent
 * coordinator, it does NOT re-implement planning, follow-ups, idempotency or locking. A recognized
 * utterance becomes a normal {@link SubmitAnswerRequest} with {@link InputMode#VOICE_REALTIME}, runs
 * through {@link FixedAnswerService#claim}/{@code process} (Redis admission gate, pessimistic turn
 * lock, requestId idempotency, fixed flow policy, AI follow-up, evaluation hand-off) and the next
 * question is synthesized to speech. The WebSocket layer therefore inherits every correctness
 * guarantee of the HTTP/SSE pipeline instead of duplicating it.
 */
public class VoiceTurnOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(VoiceTurnOrchestrator.class);

  private final FixedAnswerService answers;
  private final InterviewSessionRepository sessions;
  private final InterviewTurnRepository turns;
  private final RealtimeTtsClient tts;

  public VoiceTurnOrchestrator(FixedAnswerService answers,
                               InterviewSessionRepository sessions,
                               InterviewTurnRepository turns,
                               RealtimeTtsClient tts) {
    this.answers = answers;
    this.sessions = sessions;
    this.turns = turns;
    this.tts = tts;
  }

  /**
   * Advance the interview by one recognized answer. Runs the full claim/process transaction path
   * and synthesizes the next question. TTS failure never fails the turn — the question text is still
   * returned so the client can show it.
   */
  public VoiceTurnOutcome submitAnswer(CurrentUser user, UUID sessionId, String answerText) {
    return submitAnswer(user, sessionId, answerText, null);
  }

  public record TurnBinding(int turnNo, long version, boolean recruitment) {
    public TurnBinding(int turnNo,long version) {this(turnNo,version,false);}
  }

  public TurnBinding bind(CurrentUser user, UUID sessionId, int expectedTurnNo) {
    var session=sessions.findBySessionIdAndUserAccountId(sessionId,user.databaseId()).orElseThrow();
    if(session.getCurrentTurnNo()!=expectedTurnNo)
      throw new interview.pilot.common.exception.BusinessException("ANSWER_VERSION_CONFLICT", "面试进度已变更，请重新连接", org.springframework.http.HttpStatus.CONFLICT);
    return new TurnBinding(expectedTurnNo,session.getVersion(),session.isRecruitment());
  }

  public VoiceTurnOutcome submitAnswer(CurrentUser user, UUID sessionId, String answerText, TurnBinding binding) {
    String trimmed = answerText == null ? "" : answerText.trim();
    SubmitAnswerRequest request = new SubmitAnswerRequest(
        UUID.randomUUID(), trimmed, InputMode.VOICE_REALTIME, null,
        binding==null?null:binding.turnNo(),binding==null?null:binding.version());

    FixedAnswerClaim claim = answers.claim(user, sessionId, request);
    FixedAnswerResult result = answers.process(claim);

    if (result.nextTurn() == null) {
      log.info("[realtime {}] answer ended the interview, status={}", sessionId, result.sessionStatus());
      return VoiceTurnOutcome.ended(trimmed, result.sessionStatus(),
          result.completedTurnNo(), result.idempotentReplay());
    }
    InterviewTurnView next = result.nextTurn();
    byte[] wav = synthesize(next.question());
    return new VoiceTurnOutcome(
        trimmed, false, result.sessionStatus(), result.completedTurnNo(),
        next.turnNo(), next.question(), wav, tts.available() && wav.length > 0,
        result.idempotentReplay());
  }

  /**
   * Re-speak the currently pending question right after connect (covers refresh / reconnect and
   * guarantees the candidate hears the interviewer without pressing anything). Does not advance the
   * turn. Returns {@code null} when there is no askable current turn.
   */
  public VoiceTurnOutcome speakCurrentTurn(CurrentUser user, UUID sessionId) {
    InterviewSessionEntity session = sessions
        .findBySessionIdAndUserAccountId(sessionId, user.databaseId()).orElse(null);
    if (session == null || session.getStatus() != SessionStatus.INTERVIEWING) {
      return null;
    }
    session.requireAnswerWindow();
    InterviewTurnEntity current = turns
        .findBySessionIdAndTurnNo(session.getId(), session.getCurrentTurnNo()).orElse(null);
    if (current == null || current.getQuestionText() == null || current.getQuestionText().isBlank()) {
      return null;
    }
    byte[] wav = synthesize(current.getQuestionText());
    return new VoiceTurnOutcome(
        "", false, session.getStatus(), current.getTurnNo(), current.getTurnNo(),
        current.getQuestionText(), wav, tts.available() && wav.length > 0, false);
  }

  private byte[] synthesize(String question) {
    if (!tts.available()) {
      return new byte[0];
    }
    try {
      byte[] pcm = tts.synthesize(question);
      return PcmWavEncoder.toWav(pcm, tts.sampleRate());
    } catch (RuntimeException ex) {
      // Degradable: text still flows; never break the turn because speech synthesis failed.
      log.warn("[realtime] TTS degraded to text-only: {}", ex.toString());
      return new byte[0];
    }
  }

  /** Result of one realtime turn; {@link #nextQuestionWav()} is a complete WAV (may be empty). */
  public record VoiceTurnOutcome(
      String userAnswer,
      boolean ended,
      SessionStatus sessionStatus,
      int completedTurnNo,
      Integer nextTurnNo,
      String nextQuestion,
      byte[] nextQuestionWav,
      boolean speechReady,
      boolean idempotentReplay) {

    static VoiceTurnOutcome ended(String answer, SessionStatus status,
                                  int completedTurnNo, boolean replay) {
      return new VoiceTurnOutcome(answer, true, status, completedTurnNo, null, null,
          new byte[0], false, replay);
    }

    public String nextQuestionWavBase64() {
      return Base64.getEncoder().encodeToString(nextQuestionWav);
    }
  }
}
