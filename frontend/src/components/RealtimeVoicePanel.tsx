import { useEffect, useRef } from 'react'
import { RealtimeTranscript } from './RealtimeTranscript'
import { RealtimeVoiceOrb } from './RealtimeVoiceOrb'
import { useRealtimeVoice } from '../voice/useRealtimeVoiceInterview'

interface RealtimeVoicePanelProps {
  sessionId: string
  /** Current question from the REST snapshot, shown until the first realtime message arrives. */
  currentQuestion: string | null
  /** Called after an answer is committed or a new question arrives, to refresh the conversation. */
  onTurnChanged: () => void
  /** Called when the server signals the interview has ended (evaluation starts). */
  onEnded: () => void
  /** Fall back to the legacy record → upload → transcribe workflow. */
  onSwitchToRecording: () => void
}

const CONNECTION_LABEL: Record<string, string> = {
  idle: '未连接',
  connecting: '连接中…',
  open: '已连接',
  closed: '已断开',
}

/**
 * Talk-through voice panel: opens the WebSocket once the interview is underway and then runs the
 * whole conversation hands-free — live subtitles, silence auto-submit, spoken next question. Keeps
 * explicit "submit now", reconnect and a legacy-recording fallback for controllability.
 */
export function RealtimeVoicePanel({
  sessionId,
  currentQuestion,
  onTurnChanged,
  onEnded,
  onSwitchToRecording,
}: RealtimeVoicePanelProps) {
  const rt = useRealtimeVoice({
    onQuestion: () => onTurnChanged(),
    onAnswerCommitted: () => onTurnChanged(),
    onInterviewEnded: () => onEnded(),
  })
  const startedRef = useRef(false)

  useEffect(() => {
    if (startedRef.current) return
    startedRef.current = true
    rt.start(sessionId)
    return () => rt.stop()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  const disconnected = rt.connection === 'closed' || rt.connection === 'idle'
  const interviewer = rt.interviewer ?? currentQuestion
  // Manual-confirm mode: the submit action is only meaningful while listening and there is speech.
  const hasContent = rt.finalized.length > 0 || !!rt.partial

  return (
    <section className="rt-panel">
      <div className="rt-toolbar">
        <span className={`rt-conn rt-conn--${rt.connection}`}>
          <span className="rt-conn-dot" aria-hidden="true" />
          {CONNECTION_LABEL[rt.connection] ?? rt.connection}
        </span>
      </div>

      <RealtimeVoiceOrb connection={rt.connection} phase={rt.phase} micLevel={rt.micLevel} />

      <RealtimeTranscript
        partial={rt.partial}
        finalized={rt.finalized}
        interviewer={interviewer}
      />

      {rt.error && (
        <div className="error-notice rt-error" role="alert">
          <p>{rt.error}</p>
          <button type="button" className="button button-secondary" onClick={rt.clearError}>知道了</button>
        </div>
      )}
      {rt.notice && !rt.error && (
        <p className="voice-note" role="status">{rt.notice}</p>
      )}

      <div className="rt-actions">
        {disconnected ? (
          <button
            type="button"
            className="button button-primary"
            onClick={() => { startedRef.current = true; rt.start(sessionId) }}
          >
            重新连接语音
          </button>
        ) : (
          <button
            type="button"
            className="button button-primary"
            disabled={rt.phase !== 'listening' || !hasContent}
            onClick={rt.submitNow}
          >
            提交回答
          </button>
        )}
        <button
          type="button"
          className="button button-secondary"
          disabled={rt.connection === 'connecting'}
          onClick={() => (disconnected ? rt.start(sessionId) : rt.stop())}
        >
          {disconnected ? '连接' : '断开语音'}
        </button>
        <button type="button" className="button button-secondary" onClick={onSwitchToRecording}>
          切换录音模式
        </button>
      </div>
      <p className="rt-tip">直接说话，识别内容会实时显示但不会自动发送；说完后点「提交回答」手动确认。面试官发言时麦克风会自动暂停以防回声。</p>
    </section>
  )
}
