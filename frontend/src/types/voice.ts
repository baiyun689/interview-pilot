/** 语音能力与录音相关的后端契约类型（对应后端 voice 模块，计划 §8.1-§8.5）。 */

/** 后端 VoiceRecordingStatus 枚举。 */
export type VoiceRecordingStatus =
  | 'RECEIVING'
  | 'UPLOADED'
  | 'TRANSCRIBING'
  | 'READY'
  | 'ATTACHED'
  | 'FAILED'
  | 'DISCARDED'

/** 后端 QuestionSpeechViewStatus 枚举；NOT_AVAILABLE 为纯视图态（无语音的 turn）。 */
export type QuestionSpeechViewStatus =
  | 'PENDING'
  | 'SYNTHESIZING'
  | 'READY'
  | 'FAILED'
  | 'NOT_AVAILABLE'

/** GET /api/voice/capabilities 响应（计划 §8.1）。 */
export interface VoiceCapabilities {
  enabled: boolean
  supportedMimeTypes: string[]
  maxRecordingSeconds: number
  maxUploadBytes: number
  ttsEnabled: boolean
}

/** 录音视图（计划 §8.3）：rawTranscript 仅在 READY 时非空。 */
export interface VoiceRecordingView {
  recordingId: string
  turnNo: number
  status: VoiceRecordingStatus
  rawTranscript: string | null
  durationMillis: number | null
  retryable: boolean
  safeError: string | null
}

/** 上传录音的 202 回执（计划 §8.2）。 */
export interface VoiceRecordingUploadReceipt {
  recordingId: string
  status: VoiceRecordingStatus
  transcriptionTaskId: string | null
}

/** 问题语音视图（计划 §8.5）：speechId/mediaUrl 在 NOT_AVAILABLE 时为 null。 */
export interface QuestionSpeechView {
  speechId: string | null
  status: QuestionSpeechViewStatus
  mediaUrl: string | null
  retryable: boolean
  safeError: string | null
}
