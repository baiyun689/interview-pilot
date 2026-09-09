import { getAccessToken } from './request'

/** Server -> client message shapes (mirror the backend WsOutbound records). */
export interface VoiceControlMessage {
  type: 'control'
  action:
    | 'welcome'
    | 'answer_committed'
    | 'thinking'
    | 'interview_ended'
    | 'idle_warning'
    | 'idle_timeout'
    | 'muted'
    | 'unmuted'
  message?: string
  timestamp?: number
  turnNo?: number | null
  status?: string | null
}

export interface VoiceSubtitleMessage {
  type: 'subtitle'
  text: string
  isFinal: boolean
}

export interface VoiceTextMessage {
  type: 'text'
  content: string
  turnNo: number
}

export interface VoiceAudioMessage {
  type: 'audio'
  data: string
  text: string
  turnNo: number
}

export interface VoiceErrorMessage {
  type: 'error'
  code: string
  message: string
}

export type VoiceServerMessage =
  | VoiceControlMessage
  | VoiceSubtitleMessage
  | VoiceTextMessage
  | VoiceAudioMessage
  | VoiceErrorMessage

export interface VoiceSocketHandlers {
  onOpen?: () => void
  onClose?: (ev: CloseEvent) => void
  onControl?: (msg: VoiceControlMessage) => void
  onSubtitle?: (msg: VoiceSubtitleMessage) => void
  onText?: (msg: VoiceTextMessage) => void
  onAudio?: (msg: VoiceAudioMessage) => void
  onError?: (msg: VoiceErrorMessage) => void
}

const DEFAULT_PATH = '/ws/voice-interview'
const MAX_RECONNECT = 3
const RECONNECT_DELAY_MS = 2_000

function buildUrl(sessionId: string, path: string): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const base = `${scheme}://${window.location.host}${path}/${sessionId}`
  const token = getAccessToken()
  return token ? `${base}?token=${encodeURIComponent(token)}` : base
}

/**
 * Thin, dependency-free WebSocket client for the realtime voice interview. Reconnects a bounded
 * number of times after a non-normal close (re-reading the access token so a refresh is picked up),
 * and exposes only the two outbound frames the server accepts (audio / control).
 */
export class VoiceInterviewSocket {
  private ws: WebSocket | null = null
  private reconnectAttempts = 0
  private reconnectTimer: number | null = null
  private manuallyClosed = false

  constructor(
    private readonly sessionId: string,
    private readonly handlers: VoiceSocketHandlers,
    private readonly path: string = DEFAULT_PATH,
  ) {}

  connect(): void {
    this.manuallyClosed = false
    this.open()
  }

  private open(): void {
    const url = buildUrl(this.sessionId, this.path)
    let ws: WebSocket
    try {
      ws = new WebSocket(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.ws = ws
    ws.binaryType = 'arraybuffer'

    ws.onopen = () => {
      this.reconnectAttempts = 0
      this.handlers.onOpen?.()
    }

    ws.onmessage = (event) => {
      if (typeof event.data !== 'string') return
      let msg: VoiceServerMessage
      try {
        msg = JSON.parse(event.data) as VoiceServerMessage
      } catch {
        return
      }
      switch (msg.type) {
        case 'control':
          this.handlers.onControl?.(msg)
          break
        case 'subtitle':
          this.handlers.onSubtitle?.(msg)
          break
        case 'text':
          this.handlers.onText?.(msg)
          break
        case 'audio':
          this.handlers.onAudio?.(msg)
          break
        case 'error':
          this.handlers.onError?.(msg)
          break
      }
    }

    ws.onerror = () => {
      // onclose follows and drives reconnect; nothing to do here.
    }

    ws.onclose = (event) => {
      this.handlers.onClose?.(event)
      if (!this.manuallyClosed && event.code !== 1000) {
        this.scheduleReconnect()
      }
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= MAX_RECONNECT) {
      this.handlers.onError?.({
        type: 'error',
        code: 'SOCKET_GONE',
        message: '语音连接已断开且多次重连失败，请刷新页面重试',
      })
      return
    }
    this.reconnectAttempts += 1
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = window.setTimeout(() => this.open(), RECONNECT_DELAY_MS)
  }

  sendAudio(base64Pcm: string): void {
    this.send({ type: 'audio', data: base64Pcm })
  }

  sendControl(action: string, text?: string, turnNo?: number): void {
    this.send({ type: 'control', action, ...(text ? { text } : {}), ...(turnNo != null ? {turnNo} : {}) })
  }

  private send(payload: Record<string, unknown>): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(payload))
    }
  }

  get ready(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }

  close(): void {
    this.manuallyClosed = true
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.onclose = null
      this.ws.close()
      this.ws = null
    }
  }
}
