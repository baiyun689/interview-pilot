interface RealtimeTranscriptProps {
  /** In-flight partial transcript (replaced on every ASR delta). */
  partial: string
  /** Committed final sentences of the current answer. */
  finalized: string[]
  /** Interviewer's current question (spoken + shown). */
  interviewer: string | null
}

/**
 * Live conversation transcript: interviewer question on top, the candidate's committed sentences
 * and the in-flight partial below. Pure presentational, styled by global CSS (no animation deps).
 * The phase/status visual lives in <RealtimeVoiceOrb/>.
 */
export function RealtimeTranscript({ partial, finalized, interviewer }: RealtimeTranscriptProps) {
  const answer = finalized.join('')
  return (
    <div className="rt-transcript" aria-live="polite">
      {interviewer ? (
        <div className="rt-bubble rt-bubble--interviewer">
          <span className="rt-bubble-role">面试官</span>
          <span className="rt-bubble-text">{interviewer}</span>
        </div>
      ) : null}

      {answer || partial ? (
        <div className="rt-bubble rt-bubble--candidate">
          <span className="rt-bubble-role">我</span>
          <span className="rt-bubble-text">
            {answer}
            {partial ? <span className="rt-partial"> {partial}</span> : null}
          </span>
        </div>
      ) : (
        <div className="rt-hint">连接成功后直接说话，识别内容会实时显示，确认无误后点「提交回答」。</div>
      )}
    </div>
  )
}
