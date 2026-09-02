import { useCallback, useEffect, useRef, useState } from 'react'
import {
  discardVoiceRecording,
  getQuestionSpeech,
  getVoiceRecording,
  retryQuestionSpeech,
  retryVoiceRecording,
  uploadVoiceRecording,
} from '../api/voice'
import type { InterviewSession } from '../types/interview'
import type { QuestionSpeechView } from '../types/voice'
import { createRequestId } from './ids'
import { useVoiceRecorder, type VoiceRecorderController, type VoiceRecorderEnvironment } from './useVoiceRecorder'

/**
 * 面试页语音答题流转（计划 §13.2）。页面级状态机由本 hook 派生：
 *
 *   IDLE → RECORDING（recorder.state RECORDING/PAUSED）→ RECORDED → UPLOADING
 *        → TRANSCRIBING → TRANSCRIPT_READY（或 TRANSCRIPT_FAILED）
 *   SUBMITTING 由页面既有 SSE 提交态（submitting）承担；TEXT_FALLBACK 为文字逃生通道。
 *
 * 设计决策（都在代码注释中说明缘由）：
 * 1. recorder controller 由本 hook 持有（页面经 VoiceRecorder 受控渲染），保证
 *    上传/转写/提交流程能读取 blob、计时与状态。
 * 2. uploadRequestId 每次录音尝试只生成一次（createRequestId），上传重试复用
 *    同一个 id——后端按 uploadRequestId 幂等，重试不会产生重复录音。
 * 3. 显式放弃（改用文字回答 / 重新录音）时对已上传的录音 best-effort 调用
 *    discardVoiceRecording（忽略失败），避免录音悬挂到保留期清理。
 * 4. 页面刷新恢复：上传成功后把 { turnNo, recordingId, uploadRequestId } 写入
 *    sessionStorage；挂载后若当前轮匹配则按 recordingId 继续轮询转写状态。
 * 5. 轮询与 setTimeout/Interval 都以代数（generation）作废：换题/卸载后旧回调
 *    不再写状态，也不会误杀新一轮的轮询。
 */

export type VoiceTurnPhase =
  | 'IDLE'
  | 'RECORDING'
  | 'RECORDED'
  | 'UPLOADING'
  | 'TRANSCRIBING'
  | 'TRANSCRIPT_READY'
  | 'TRANSCRIPT_FAILED'
  | 'TEXT_FALLBACK'

export interface QuestionSpeechState {
  /** LOADING=正在拉取/合成；READY=可播放；FAILED=生成或拉取失败；NOT_AVAILABLE=无语音 */
  status: 'LOADING' | 'READY' | 'FAILED' | 'NOT_AVAILABLE'
  view: QuestionSpeechView | null
}

export interface TranscribeError {
  retryable: boolean
  message: string
}

export interface VoiceTurnFlow {
  recorder: VoiceRecorderController
  phase: VoiceTurnPhase
  speech: QuestionSpeechState
  /** 转写结果（可编辑），TRANSCRIPT_READY 后由页面绑定 textarea */
  transcript: string
  setTranscript(text: string): void
  recordingId: string | null
  uploadProgress: number | null
  uploadError: unknown
  transcribeError: TranscribeError | null
  /** 上传录音（RECORDED 状态由 VoiceRecorder 回调；防重复点击） */
  upload(blob: Blob): void
  /** 转写失败后重试 ASR（仅 retryable） */
  retryTranscription(): void
  /** 语音生成失败后重新合成（仅 retryable） */
  retrySpeech(): void
  /** 重录：放弃已上传的旧录音（best-effort discard）并回到 IDLE */
  resetRecording(): void
  /** 显式改用文字回答：best-effort discard 已上传录音并释放麦克风 */
  fallbackToText(): void
}

export interface VoiceTurnFlowOptions {
  sessionId: string
  session: InterviewSession | undefined
  maxRecordingSeconds?: number
  env?: Partial<VoiceRecorderEnvironment>
}

export const VOICE_RECORDING_STORAGE_KEY = (sessionId: string) => `interview-voice-recording:${sessionId}`
export const TRANSCRIPTION_POLL_MS = 1_000
export const SPEECH_POLL_MS = 1_500
/** 转写状态连续查询失败 N 次后视为不可达，停止轮询并报错 */
const MAX_POLL_ERRORS = 5
/** 本轮处理失败（turn FAILED）时需要清空的语音阶段（旧录音尝试的产物）。
 *  IDLE/RECORDING/RECORDED 表示用户已重新开始新一轮尝试，不打断。 */
const RESETTABLE_ON_FAILED: ReadonlySet<VoiceTurnPhase> = new Set([
  'UPLOADING', 'TRANSCRIBING', 'TRANSCRIPT_READY', 'TRANSCRIPT_FAILED',
])

interface PersistedVoiceRecording {
  sessionId: string
  turnNo: number
  recordingId: string
  uploadRequestId: string
}

function readPersistedRecording(sessionId: string): PersistedVoiceRecording | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(VOICE_RECORDING_STORAGE_KEY(sessionId)) ?? 'null') as PersistedVoiceRecording | null
    if (value && typeof value?.turnNo === 'number' && typeof value?.recordingId === 'string' && value.sessionId === sessionId) {
      return value
    }
    return null
  } catch {
    return null
  }
}

function persistRecording(sessionId: string, entry: PersistedVoiceRecording) {
  try {
    sessionStorage.setItem(VOICE_RECORDING_STORAGE_KEY(sessionId), JSON.stringify(entry))
  } catch {
    // 存储不可用时仅丢失刷新恢复能力，不影响本轮流程
  }
}

function removePersistedRecording(sessionId: string) {
  try {
    sessionStorage.removeItem(VOICE_RECORDING_STORAGE_KEY(sessionId))
  } catch {
    // 清理失败忽略
  }
}

export function useVoiceTurnFlow(options: VoiceTurnFlowOptions): VoiceTurnFlow {
  const { sessionId, session } = options
  const recorder = useVoiceRecorder({ env: options.env, maxRecordingSeconds: options.maxRecordingSeconds })
  const isVoice = session?.interviewMode === 'VOICE'

  const [phase, setPhase] = useState<VoiceTurnPhase>('IDLE')
  const [speech, setSpeech] = useState<QuestionSpeechState>({ status: 'LOADING', view: null })
  const [transcript, setTranscriptState] = useState('')
  const [recordingId, setRecordingId] = useState<string | null>(null)
  const [uploadProgress, setUploadProgress] = useState<number | null>(null)
  const [uploadError, setUploadError] = useState<unknown>()
  const [transcribeError, setTranscribeError] = useState<TranscribeError | null>(null)

  const generationRef = useRef(0)
  const turnRef = useRef(0)
  const phaseRef = useRef<VoiceTurnPhase>('IDLE')
  const speechRef = useRef<QuestionSpeechState>({ status: 'LOADING', view: null })
  speechRef.current = speech
  const recordingIdRef = useRef<string | null>(null)
  const uploadRequestIdRef = useRef<string | null>(null)
  const transcriptionPollRef = useRef<number | null>(null)
  const speechTimerRef = useRef<number | null>(null)
  const pollErrorCountRef = useRef(0)
  /** 进行中的上传；改用文字/换题/卸载时中止，避免放弃后仍落地录音 */
  const uploadAbortRef = useRef<AbortController | null>(null)
  /** 本轮 FAILED 是否已重置过：防止 2 秒会话轮询反复触发误杀新一轮录音 */
  const failedResetRef = useRef(false)

  const transition = useCallback((next: VoiceTurnPhase) => {
    phaseRef.current = next
    setPhase(next)
  }, [])

  const stopTranscriptionPoll = useCallback(() => {
    if (transcriptionPollRef.current !== null) {
      window.clearInterval(transcriptionPollRef.current)
      transcriptionPollRef.current = null
    }
  }, [])

  const clearSpeechTimer = useCallback(() => {
    if (speechTimerRef.current !== null) {
      window.clearTimeout(speechTimerRef.current)
      speechTimerRef.current = null
    }
  }, [])

  const abortUpload = useCallback(() => {
    uploadAbortRef.current?.abort()
    uploadAbortRef.current = null
  }, [])

  /** 每 1 秒查询录音视图，直到 READY/ATTACHED（→ 可编辑转写）或 FAILED。 */
  const startTranscriptionPoll = useCallback((recId: string, generation: number) => {
    stopTranscriptionPoll()
    pollErrorCountRef.current = 0
    transcriptionPollRef.current = window.setInterval(() => {
      getVoiceRecording(sessionId, recId)
        .then((view) => {
          if (generationRef.current !== generation) return
          if (view.status === 'READY' || view.status === 'ATTACHED') {
            stopTranscriptionPoll()
            setTranscriptState(view.rawTranscript ?? '')
            transition('TRANSCRIPT_READY')
          } else if (view.status === 'FAILED') {
            stopTranscriptionPoll()
            setTranscribeError({
              retryable: view.retryable,
              message: view.safeError ?? '录音转写失败，请重试',
            })
            transition('TRANSCRIPT_FAILED')
          } else if (view.status === 'DISCARDED') {
            // 防御（评审）：录音已被放弃（如刷新前已切换到文字），终止轮询回到 IDLE；
            // 清除恢复条目与本地录音，重录时生成新的 uploadRequestId，不复用已放弃录音的幂等键
            stopTranscriptionPoll()
            removePersistedRecording(sessionId)
            uploadRequestIdRef.current = null
            recorder.reset()
            transition('IDLE')
          }
          // RECEIVING / UPLOADED / TRANSCRIBING：继续轮询
        })
        .catch(() => {
          // 单次查询失败继续轮询；连续失败视为状态不可达
          pollErrorCountRef.current += 1
          if (pollErrorCountRef.current >= MAX_POLL_ERRORS && generationRef.current === generation) {
            stopTranscriptionPoll()
            setTranscribeError({ retryable: false, message: '无法获取转写状态，请重新录音或改用文字输入' })
            transition('TRANSCRIPT_FAILED')
          }
        })
    }, TRANSCRIPTION_POLL_MS)
  }, [recorder, sessionId, stopTranscriptionPoll, transition])

  /** 拉取题目语音：202（PENDING/SYNTHESIZING）时 1.5 秒后继续轮询。 */
  const pollSpeech = useCallback((turnNo: number, generation: number) => {
    clearSpeechTimer()
    getQuestionSpeech(sessionId, turnNo)
      .then((response) => {
        if (generationRef.current !== generation) return
        const view = response.data
        if (response.status === 202 || view.status === 'PENDING' || view.status === 'SYNTHESIZING') {
          speechTimerRef.current = window.setTimeout(() => pollSpeech(turnNo, generation), SPEECH_POLL_MS)
          return
        }
        if (view.status === 'READY') setSpeech({ status: 'READY', view })
        else if (view.status === 'FAILED') setSpeech({ status: 'FAILED', view })
        else setSpeech({ status: 'NOT_AVAILABLE', view })
      })
      .catch(() => {
        if (generationRef.current !== generation) return
        // 拉取失败不阻塞答题：仅提示语音不可用（无法重试，不显示重试按钮）
        setSpeech({ status: 'FAILED', view: null })
      })
  }, [clearSpeechTimer, sessionId])

  /** 换题/卸载：作废全部异步流程并中止进行中的上传。 */
  useEffect(() => {
    return () => {
      generationRef.current += 1
      stopTranscriptionPoll()
      clearSpeechTimer()
      abortUpload()
    }
  }, [abortUpload, clearSpeechTimer, stopTranscriptionPoll])

  /** 新一轮语音流程：清理旧轮状态，按需恢复刷新中的录音并拉取题目语音。 */
  useEffect(() => {
    const turnNo = session?.currentTurnNo ?? 0
    if (turnNo === turnRef.current) return
    turnRef.current = turnNo
    generationRef.current += 1
    clearSpeechTimer()
    stopTranscriptionPoll()
    abortUpload()
    recorder.reset()
    transition('IDLE')
    setTranscript('')
    setRecordingId(null)
    recordingIdRef.current = null
    uploadRequestIdRef.current = null
    setUploadProgress(null)
    setUploadError(undefined)
    setTranscribeError(null)
    setSpeech({ status: 'LOADING', view: null })
    // 过期恢复条目清理（旧轮的录音已不再可恢复）
    const saved = readPersistedRecording(sessionId)
    if (saved && saved.turnNo !== turnNo) removePersistedRecording(sessionId)
    if (!isVoice || turnNo === 0 || session?.status !== 'INTERVIEWING') return
    const currentTurn = session.turns.find((turn) => turn.turnNo === turnNo)
    if (!currentTurn || currentTurn.status !== 'ASKED') return
    // 刷新恢复：本轮已有上传录音时按 recordingId 继续转写轮询
    const restored = readPersistedRecording(sessionId)
    if (restored && restored.turnNo === turnNo) {
      recordingIdRef.current = restored.recordingId
      setRecordingId(restored.recordingId)
      uploadRequestIdRef.current = restored.uploadRequestId
      transition('TRANSCRIBING')
      startTranscriptionPoll(restored.recordingId, generationRef.current)
    }
    // 题目语音与录音流程独立：始终拉取
    void pollSpeech(turnNo, generationRef.current)
  }, [abortUpload, clearSpeechTimer, isVoice, pollSpeech, recorder, session, sessionId, startTranscriptionPoll, stopTranscriptionPoll, transition])

  /** recorder 状态 → 页面阶段（RECORDING/PAUSED → RECORDING，停止 → RECORDED）。 */
  useEffect(() => {
    if (!isVoice) return
    if (recorder.state === 'RECORDING' || recorder.state === 'PAUSED') {
      transition('RECORDING')
    } else if (recorder.state === 'RECORDED' && (phaseRef.current === 'IDLE' || phaseRef.current === 'RECORDING')) {
      transition('RECORDED')
    }
    // REQUESTING_PERMISSION / ERROR / IDLE 由 VoiceRecorder 内部呈现
  }, [isVoice, recorder.state, transition])

  /** 显式放弃录音（best-effort，失败忽略）：用于改用文字回答与重录前的旧录音释放。 */
  const discardBestEffort = useCallback(() => {
    const recId = recordingIdRef.current
    if (!recId) return
    void discardVoiceRecording(sessionId, recId).catch(() => { /* 释放失败不影响本地状态 */ })
  }, [sessionId])

  /** 本轮处理失败（后端处理异常后 turn 变 FAILED，评审 Critical）：放弃旧录音尝试，
   *  回到可重录/文字回退的 IDLE。只在失败首次出现时重置一次（failedResetRef），
   *  避免 2 秒会话轮询反复触发误杀新一轮录音。 */
  useEffect(() => {
    const currentTurn = session?.turns.find((turn) => turn.turnNo === turnRef.current)
    if (currentTurn?.status === 'FAILED' && !failedResetRef.current) {
      failedResetRef.current = true
      if (isVoice && RESETTABLE_ON_FAILED.has(phaseRef.current)) {
        stopTranscriptionPoll()
        clearSpeechTimer()
        abortUpload()
        discardBestEffort()
        removePersistedRecording(sessionId)
        recordingIdRef.current = null
        setRecordingId(null)
        uploadRequestIdRef.current = null
        setTranscript('')
        setTranscribeError(null)
        setUploadError(undefined)
        setUploadProgress(null)
        recorder.reset()
        transition('IDLE')
      }
    } else if (currentTurn?.status !== 'FAILED') {
      failedResetRef.current = false
    }
  }, [abortUpload, clearSpeechTimer, discardBestEffort, isVoice, recorder, session, sessionId, stopTranscriptionPoll, transition])

  const upload = useCallback((blob: Blob) => {
    const turnNo = turnRef.current
    if (phaseRef.current === 'UPLOADING' || turnNo === 0 || !blob) return
    // 一次录音尝试只生成一个 uploadRequestId：重试复用，后端按它幂等去重
    uploadRequestIdRef.current ??= createRequestId()
    const uploadRequestId = uploadRequestIdRef.current
    const generation = generationRef.current
    // 上传可被「改用文字回答」/换题/卸载中止（评审）
    const controller = new AbortController()
    uploadAbortRef.current = controller
    transition('UPLOADING')
    setUploadProgress(null)
    setUploadError(undefined)
    setTranscribeError(null)
    uploadVoiceRecording(sessionId, turnNo, uploadRequestId, blob, (progress) => {
      if (generationRef.current === generation) {
        setUploadProgress(progress.total > 0 ? Math.round((progress.loaded / progress.total) * 100) : null)
      }
    }, controller.signal)
      .then((response) => {
        if (generationRef.current !== generation || phaseRef.current !== 'UPLOADING') return
        const nextRecordingId = response.data.recordingId
        recordingIdRef.current = nextRecordingId
        setRecordingId(nextRecordingId)
        persistRecording(sessionId, { sessionId, turnNo, recordingId: nextRecordingId, uploadRequestId })
        transition('TRANSCRIBING')
        startTranscriptionPoll(nextRecordingId, generation)
      })
      .catch((error) => {
        if (generationRef.current !== generation) return
        if (error instanceof DOMException && error.name === 'AbortError') return // 用户主动放弃上传
        // 上传失败回到 RECORDED：可重试（同一 uploadRequestId）或重录
        setUploadError(error)
        transition('RECORDED')
      })
  }, [sessionId, startTranscriptionPoll, transition])

  const retryTranscription = useCallback(() => {
    const recId = recordingIdRef.current
    const generation = generationRef.current
    if (!recId) return
    transition('TRANSCRIBING')
    setTranscribeError(null)
    retryVoiceRecording(sessionId, recId)
      .then(() => {
        if (generationRef.current === generation) startTranscriptionPoll(recId, generation)
      })
      .catch(() => {
        if (generationRef.current !== generation) return
        setTranscribeError({ retryable: false, message: '无法重新发起转写，请重新录音或改用文字输入' })
        transition('TRANSCRIPT_FAILED')
      })
  }, [sessionId, startTranscriptionPoll, transition])

  const retrySpeech = useCallback(() => {
    const view = speechRef.current.view
    if (speechRef.current.status !== 'FAILED' || !view?.retryable) return
    const turnNo = turnRef.current
    const generation = generationRef.current
    setSpeech({ status: 'LOADING', view: null })
    retryQuestionSpeech(sessionId, turnNo)
      .then(() => {
        if (generationRef.current === generation) pollSpeech(turnNo, generation)
      })
      .catch(() => {
        if (generationRef.current === generation) setSpeech({ status: 'FAILED', view: null })
      })
  }, [pollSpeech, sessionId])

  const resetRecording = useCallback(() => {
    stopTranscriptionPoll()
    abortUpload()
    discardBestEffort()
    // 清除恢复条目（评审）：否则刷新后会把已放弃的录音重新恢复出来轮询
    removePersistedRecording(sessionId)
    recordingIdRef.current = null
    setRecordingId(null)
    uploadRequestIdRef.current = null
    setTranscript('')
    setTranscribeError(null)
    setUploadError(undefined)
    setUploadProgress(null)
    recorder.reset()
    transition('IDLE')
  }, [abortUpload, discardBestEffort, recorder, sessionId, stopTranscriptionPoll, transition])

  const fallbackToText = useCallback(() => {
    // 显式改用文字回答：放弃当前录音（best-effort 通知后端），释放麦克风并隐藏语音面板。
    // 放弃的是"尚未绑定答案"的录音，后端允许 discard；失败也不影响本地切换。
    // 同步清除恢复条目并中止进行中的上传（评审）：刷新后不得复活已放弃的录音。
    stopTranscriptionPoll()
    clearSpeechTimer()
    abortUpload()
    discardBestEffort()
    removePersistedRecording(sessionId)
    recorder.reset()
    setTranscribeError(null)
    setUploadError(undefined)
    setUploadProgress(null)
    transition('TEXT_FALLBACK')
  }, [abortUpload, clearSpeechTimer, discardBestEffort, recorder, sessionId, stopTranscriptionPoll, transition])

  const setTranscript = useCallback((text: string) => setTranscriptState(text), [])

  return {
    recorder,
    phase,
    speech,
    transcript,
    setTranscript,
    recordingId,
    uploadProgress,
    uploadError,
    transcribeError,
    upload,
    retryTranscription,
    retrySpeech,
    resetRecording,
    fallbackToText,
  }
}
