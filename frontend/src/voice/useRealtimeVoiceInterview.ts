import { useCallback, useEffect, useRef, useState } from 'react'
import {
  VoiceInterviewSocket,
  type VoiceAudioMessage,
  type VoiceControlMessage,
} from '../api/voiceRealtime'
import { useRealtimeMic } from './useRealtimeMic'

export type RealtimePhase = 'idle' | 'listening' | 'thinking' | 'speaking'
export type RealtimeConnection = 'idle' | 'connecting' | 'open' | 'closed'

export interface UseRealtimeVoiceOptions {
  onQuestion?: (question: string, turnNo: number) => void
  onAnswerCommitted?: (answer: string) => void
  onInterviewEnded?: (status: string | null | undefined) => void
}

/**
 * Orchestrates one realtime voice interview: owns the WebSocket + mic, maintains the live
 * transcript, plays interviewer audio half-duplex (mic muted while the interviewer speaks and for
 * the server cooldown afterwards), and surfaces turn/end callbacks so the existing page can stay in
 * sync without any record/upload/poll/confirm steps.
 */
export function useRealtimeVoice(options: UseRealtimeVoiceOptions = {}) {
  const [connection, setConnection] = useState<RealtimeConnection>('idle')
  const [phase, setPhase] = useState<RealtimePhase>('idle')
  const [partial, setPartial] = useState('')
  const [finalized, setFinalized] = useState<string[]>([])
  const [interviewer, setInterviewer] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [micLevel, setMicLevel] = useState(0)
  const [ended, setEnded] = useState(false)

  const socketRef = useRef<VoiceInterviewSocket | null>(null)
  const audioRef = useRef<HTMLAudioElement | null>(null)
  const speakingFallbackRef = useRef<number | null>(null)
  const optionsRef = useRef(options)
  optionsRef.current = options
  // Latest not-yet-final partial, so a manual submit can carry the last half-sentence the server VAD
  // has not yet finalized (manual-confirm mode never relies on silence to flush it).
  const partialRef = useRef('')

  const clearSpeakingFallback = useCallback(() => {
    if (speakingFallbackRef.current !== null) {
      window.clearTimeout(speakingFallbackRef.current)
      speakingFallbackRef.current = null
    }
  }, [])

  const backToListening = useCallback(() => {
    clearSpeakingFallback()
    setPhase((current) => (current === 'speaking' || current === 'thinking' ? 'listening' : current))
  }, [clearSpeakingFallback])

  const playInterviewerAudio = useCallback((msg: VoiceAudioMessage) => {
    clearSpeakingFallback()
    setPhase('speaking')
    if (audioRef.current) {
      audioRef.current.pause()
      audioRef.current = null
    }
    const audio = new Audio(`data:audio/wav;base64,${msg.data}`)
    audioRef.current = audio
    audio.onended = () => {
      audioRef.current = null
      setPhase('listening')
    }
    audio.onerror = () => {
      audioRef.current = null
      setPhase('listening')
    }
    void audio.play().catch(() => setPhase('listening'))
  }, [clearSpeakingFallback])

  const handleControl = useCallback((msg: VoiceControlMessage) => {
    switch (msg.action) {
      case 'welcome':
        setPhase('listening')
        break
      case 'answer_committed':
        // The merged utterance is now being processed; freeze it out of the live area.
        setPartial('')
        setFinalized([])
        if (msg.message) optionsRef.current.onAnswerCommitted?.(msg.message)
        break
      case 'thinking':
        setPhase('thinking')
        break
      case 'interview_ended':
        setEnded(true)
        setPhase('idle')
        optionsRef.current.onInterviewEnded?.(msg.status)
        break
      case 'idle_warning':
        setNotice(msg.message ?? '即将断开连接')
        break
      case 'idle_timeout':
        setNotice(msg.message ?? '连接已超时断开')
        break
      case 'muted':
      case 'unmuted':
        break
      default:
        break
    }
  }, [])

  const mic = useRealtimeMic({
    onChunk: useCallback((base64: string) => {
      socketRef.current?.sendAudio(base64)
    }, []),
    onLevel: useCallback((level: number) => setMicLevel(level), []),
  })

  // Half-duplex: never stream mic frames while the interviewer is speaking / we are thinking.
  useEffect(() => {
    mic.setMuted(phase === 'speaking' || phase === 'thinking' || phase === 'idle')
  }, [phase, mic])

  useEffect(() => { partialRef.current = partial }, [partial])

  const start = useCallback((sessionId: string) => {
    if (socketRef.current) return
    setConnection('connecting')
    setError(null)
    const socket = new VoiceInterviewSocket(sessionId, {
      onOpen: () => {
        setConnection('open')
        setPhase('listening')
        void mic.start().catch(() => undefined)
      },
      onClose: () => {
        setConnection((current) => (current === 'open' ? 'closed' : current))
      },
      onControl: handleControl,
      onSubtitle: (msg) => {
        if (msg.isFinal) {
          setPartial('')
          setFinalized((current) => [...current, msg.text])
        } else {
          setPartial(msg.text)
        }
      },
      onText: (msg) => {
        setInterviewer(msg.content)
        setPhase('speaking')
        optionsRef.current.onQuestion?.(msg.content, msg.turnNo)
        // If no audio follows (TTS degraded), return to listening shortly.
        clearSpeakingFallback()
        speakingFallbackRef.current = window.setTimeout(() => setPhase('listening'), 500)
      },
      onAudio: playInterviewerAudio,
      onError: (msg) => {
        setError(msg.message)
        if (msg.code === 'SOCKET_GONE') setConnection('closed')
      },
    })
    socketRef.current = socket
    socket.connect()
  }, [mic, handleControl, playInterviewerAudio, clearSpeakingFallback])

  const stop = useCallback(() => {
    clearSpeakingFallback()
    socketRef.current?.close()
    socketRef.current = null
    if (audioRef.current) {
      audioRef.current.pause()
      audioRef.current = null
    }
    mic.stop()
    setConnection('idle')
    setPhase('idle')
  }, [mic, clearSpeakingFallback])

  // Manual confirmation: flush finalized sentences (already merged server-side) together with the
  // last in-flight partial; the server only advances the turn after this explicit submit.
  const submitNow = useCallback(() => {
    const extra = partialRef.current.trim()
    socketRef.current?.sendControl('submit', extra || undefined)
  }, [])

  useEffect(() => () => {
    clearSpeakingFallback()
    socketRef.current?.close()
    socketRef.current = null
  }, [clearSpeakingFallback])

  return {
    start,
    stop,
    submitNow,
    connection,
    phase,
    partial,
    finalized,
    interviewer,
    error,
    notice,
    micLevel,
    micActive: mic.active,
    micMuted: mic.muted,
    ended,
    clearError: () => setError(null),
    clearNotice: () => setNotice(null),
  }
}
