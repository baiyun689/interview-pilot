import type { RealtimeConnection, RealtimePhase } from '../voice/useRealtimeVoiceInterview'

interface RealtimeVoiceOrbProps {
  connection: RealtimeConnection
  phase: RealtimePhase
  /** Live microphone level 0..1, only meaningful while listening. */
  micLevel: number
}

type Visual = 'idle' | 'connecting' | RealtimePhase

const VISUAL_LABEL: Record<Visual, string> = {
  idle: '等待连接',
  connecting: '正在建立连接…',
  listening: '正在聆听，请发言',
  thinking: '面试官思考中…',
  speaking: '面试官发言中',
}

/** Five equalizer bars; baseline offsets keep the row visually balanced. */
const BAR_WEIGHTS = [0.35, 0.62, 1, 0.62, 0.35]

function MicIcon() {
  return (
    <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="9" y="3" width="6" height="11" rx="3" />
      <path d="M5.5 11.5a6.5 6.5 0 0 0 13 0" />
      <line x1="12" y1="18" x2="12" y2="21.5" />
    </svg>
  )
}

function SpeakerIcon() {
  return (
    <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 9.5v5h3.5L13 19V5L7.5 9.5H4z" />
      <path d="M16 9a4 4 0 0 1 0 6" />
      <path d="M18.2 6.8a7 7 0 0 1 0 10.4" />
    </svg>
  )
}

/**
 * The "stage" for a realtime voice interview: a glowing orb whose look tracks the conversation
 * phase — an equalizer that follows the live mic level while listening, animated sound waves while
 * the interviewer speaks, breathing dots while thinking, and a spinner while connecting.
 * Purely presentational; the parent owns all WebSocket / mic state.
 */
export function RealtimeVoiceOrb({ connection, phase, micLevel }: RealtimeVoiceOrbProps) {
  const visual: Visual = connection === 'open'
    ? phase
    : connection === 'connecting' ? 'connecting' : 'idle'
  const level = Math.max(0, Math.min(1, micLevel || 0))

  return (
    <div className={`orb orb--${visual}`} role="status" aria-live="polite">
      <span
        className="orb-core"
        style={visual === 'listening' ? { transform: `scale(${1 + level * 0.06})` } : undefined}
      >
        {visual === 'listening' && (
          <>
            <span className="orb-icon"><MicIcon /></span>
            <span className="orb-eq" aria-hidden="true">
              {BAR_WEIGHTS.map((weight, index) => (
                <span
                  key={index}
                  className="orb-eq-bar"
                  style={{ height: `${Math.round((0.14 + weight * (0.2 + level * 0.8)) * 100)}%` }}
                />
              ))}
            </span>
          </>
        )}

        {visual === 'speaking' && (
          <>
            <span className="orb-icon"><SpeakerIcon /></span>
            <span className="orb-wave" aria-hidden="true">
              {BAR_WEIGHTS.map((weight, index) => (
                <span key={index} className="orb-wave-bar" style={{ animationDelay: `${index * 0.12}s` }} />
              ))}
            </span>
          </>
        )}

        {visual === 'thinking' && (
          <span className="orb-dots" aria-hidden="true">
            <span className="orb-dot" style={{ animationDelay: '0s' }} />
            <span className="orb-dot" style={{ animationDelay: '0.15s' }} />
            <span className="orb-dot" style={{ animationDelay: '0.3s' }} />
          </span>
        )}

        {visual === 'connecting' && <span className="orb-spinner" aria-hidden="true" />}

        {visual === 'idle' && (
          <span className="orb-icon orb-icon--muted"><MicIcon /></span>
        )}
      </span>
      <span className="orb-label">{VISUAL_LABEL[visual]}</span>
    </div>
  )
}
