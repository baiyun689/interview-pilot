import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { detectMediaRecorderCapabilities } from './mediaRecorderCapabilities'

/**
 * 浏览器录音 hook（计划 §13.3）。对外暴露 VoiceRecorderController，所有浏览器
 * API（getUserMedia / MediaRecorder / AudioContext）都通过 env 注入，测试以假
 * 实现驱动，不依赖真实浏览器。
 *
 * 设计决策（都在代码中注释了缘由）：
 * 1. 5 分钟上限按「累计录音时长」计算——暂停期间不计时（对候选人更公平，
 *    暂停后可以停下来想，不消耗配额）。elapsedMs 由累积段相加得到。
 * 2. stop() 之后 tracks 保持存活：候选人在 RECORDED 状态直接试听，之后可以
 *    reset() 重录。tracks 在 reset() 或卸载时释放。
 * 3. 组件卸载/路由切换时若正在录音则「丢弃」而非自动停止：用户已经离开页面，
 *    自动停止产出的 Blob 无人查看，还可能引发意外的后续上传副作用，丢弃更安全。
 * 4. 自动停止与 4:50 警告都由 tick 驱动，基于累计录音时长判断。
 */

export type RecorderState = 'IDLE' | 'REQUESTING_PERMISSION' | 'RECORDING' | 'PAUSED' | 'RECORDED' | 'ERROR'

export type RecorderErrorKind = 'PERMISSION_DENIED' | 'UNAVAILABLE' | 'UNKNOWN'

export interface RecorderErrorInfo {
  kind: RecorderErrorKind
  message: string
}

export interface VoiceRecorderController {
  state: RecorderState
  /** 累计录音时长（毫秒），暂停期间不计入 */
  elapsedMs: number
  /** 音量电平 0..1，来自 AnalyserNode；不可用时为 0 */
  level: number
  /** 最近一次录音的 Blob；reset 后为 null */
  blob: Blob | null
  /** blob 的 Object URL，由本 hook 管理生命周期（reset/卸载时撤销） */
  blobUrl: string | null
  /** 距上限不足 warnSeconds 时置位（默认 4:50 / 5:00） */
  nearLimit: boolean
  /** 是否因到达 5 分钟上限自动停止 */
  autoStopped: boolean
  /** 环境不支持录音（无 MediaRecorder / 无可用 MIME / 无 getUserMedia） */
  unavailable: boolean
  /** 文字输入逃生通道信号：权限被拒或浏览器不支持时置位 */
  textFallback: boolean
  error: RecorderErrorInfo | null
  start(): Promise<void>
  pause(): void
  resume(): void
  stop(): Promise<Blob>
  reset(): void
}

export interface VoiceRecorderEnvironment {
  getUserMedia: (constraints: MediaStreamConstraints) => Promise<MediaStream>
  MediaRecorder: typeof MediaRecorder | null
  AudioContext: typeof AudioContext | null
  now: () => number
}

export interface VoiceRecorderOptions {
  env?: Partial<VoiceRecorderEnvironment>
  maxRecordingSeconds?: number
  warnSeconds?: number
  tickMs?: number
}

export const DEFAULT_MAX_RECORDING_SECONDS = 300
export const DEFAULT_WARNING_SECONDS = 10
export const DEFAULT_TICK_MS = 200

/** MediaRecorder 最小可用面（运行时只依赖这些成员，方便注入假实现）。 */
interface RecorderLike {
  state: 'inactive' | 'recording' | 'paused'
  start(): void
  pause(): void
  resume(): void
  stop(): void
  ondataavailable: ((event: { data: Blob }) => void) | null
  onstop: (() => void) | null
  onerror: ((event: unknown) => void) | null
}

type StopReason = 'manual' | 'auto' | 'discard' | 'error'

/** 时域采样 RMS 音量（0..1）；采样值为无符号 8 位 PCM。 */
export function computeLevel(samples: Uint8Array): number {
  let sum = 0
  for (let i = 0; i < samples.length; i++) {
    const normalized = (samples[i] - 128) / 128
    sum += normalized * normalized
  }
  return Math.min(1, Math.sqrt(sum / Math.max(1, samples.length)))
}

function defaultEnvironment(): VoiceRecorderEnvironment {
  return {
    getUserMedia: (constraints) => {
      const mediaDevices = typeof navigator !== 'undefined' ? navigator.mediaDevices : undefined
      if (!mediaDevices || typeof mediaDevices.getUserMedia !== 'function') {
        return Promise.reject(Object.assign(new Error('microphone capture is not supported'), { name: 'NotSupportedError' }))
      }
      return mediaDevices.getUserMedia(constraints)
    },
    MediaRecorder: typeof MediaRecorder !== 'undefined' ? MediaRecorder : null,
    AudioContext: typeof AudioContext !== 'undefined' ? AudioContext : null,
    now: () => Date.now(),
  }
}

function computeUnavailable(env: VoiceRecorderEnvironment): boolean {
  if (typeof env.getUserMedia !== 'function') return true
  return !detectMediaRecorderCapabilities(env.MediaRecorder).supported
}

function stopAllTracks(stream: MediaStream | null) {
  if (!stream) return
  stream.getTracks().forEach((track) => track.stop())
}

function errorKindFor(reason: unknown): RecorderErrorKind {
  const name = reason instanceof Error ? reason.name : ''
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'NotFoundError') {
    return 'PERMISSION_DENIED'
  }
  if (name === 'NotSupportedError' || name === 'SecurityError') return 'UNAVAILABLE'
  return 'UNKNOWN'
}

const ERROR_MESSAGES: Record<RecorderErrorKind, string> = {
  PERMISSION_DENIED: '未获得麦克风权限，请检查浏览器设置后重试，或改用文字输入',
  UNAVAILABLE: '当前浏览器或设备不支持语音录音，请改用文字输入',
  UNKNOWN: '无法启动录音，请稍后重试',
}

export function useVoiceRecorder(options?: VoiceRecorderOptions): VoiceRecorderController {
  // 环境与配置只读取一次（页面挂载时固定），注入的测试环境同理
  const envRef = useRef<VoiceRecorderEnvironment | null>(null)
  if (envRef.current === null) envRef.current = { ...defaultEnvironment(), ...options?.env }
  const env = envRef.current
  const maxMsRef = useRef((options?.maxRecordingSeconds ?? DEFAULT_MAX_RECORDING_SECONDS) * 1000)
  const warnMsRef = useRef((options?.warnSeconds ?? DEFAULT_WARNING_SECONDS) * 1000)
  const tickMsRef = useRef(options?.tickMs ?? DEFAULT_TICK_MS)

  const [state, setState] = useState<RecorderState>('IDLE')
  const stateRef = useRef<RecorderState>('IDLE')
  const [elapsedMs, setElapsedMs] = useState(0)
  const [level, setLevel] = useState(0)
  const [blob, setBlob] = useState<Blob | null>(null)
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [nearLimit, setNearLimit] = useState(false)
  const [autoStopped, setAutoStopped] = useState(false)
  const [error, setError] = useState<RecorderErrorInfo | null>(null)
  const [unavailable] = useState(() => computeUnavailable(env))

  const streamRef = useRef<MediaStream | null>(null)
  const recorderRef = useRef<RecorderLike | null>(null)
  const chunksRef = useRef<Blob[]>([])
  const mimeRef = useRef<string | null>(null)
  const blobRef = useRef<Blob | null>(null)
  const blobUrlRef = useRef<string | null>(null)
  const accumulatedMsRef = useRef(0)
  const segmentStartRef = useRef(0)
  const nearLimitRef = useRef(false)
  const autoStoppedRef = useRef(false)
  const inFlightRef = useRef(false)
  const liveRef = useRef(true)
  /** 每次 reset/卸载自增；stop 的异步 onstop 若发现代数过期则丢弃状态写入 */
  const generationRef = useRef(0)
  const stopResolveRef = useRef<((blob: Blob) => void) | null>(null)
  const tickRef = useRef<number | null>(null)
  const analyserRef = useRef<{
    ctx: AudioContext
    source: AudioNode
    analyser: AnalyserNode
    data: Uint8Array
  } | null>(null)

  const transition = useCallback((next: RecorderState) => {
    stateRef.current = next
    setState(next)
  }, [])

  const stopTick = useCallback(() => {
    if (tickRef.current !== null) {
      window.clearInterval(tickRef.current)
      tickRef.current = null
    }
  }, [])

  const cleanupAnalyser = useCallback(() => {
    const held = analyserRef.current
    analyserRef.current = null
    if (!held) return
    try { held.source.disconnect() } catch { /* 已断开的节点忽略 */ }
    try { held.analyser.disconnect() } catch { /* 已断开的节点忽略 */ }
    const close = held.ctx.close()
    if (close && typeof close.catch === 'function') void close.catch(() => { /* 关闭失败忽略 */ })
  }, [])

  const elapsedNow = useCallback(() => {
    const running = stateRef.current === 'RECORDING' ? env.now() - segmentStartRef.current : 0
    return accumulatedMsRef.current + Math.max(0, running)
  }, [env])

  const readLevel = useCallback(() => {
    const held = analyserRef.current
    if (!held) return 0
    try {
      held.analyser.getByteTimeDomainData(held.data)
      return computeLevel(held.data)
    } catch {
      return 0
    }
  }, [])

  const fail = useCallback((kind: RecorderErrorKind, message: string) => {
    generationRef.current += 1
    if (recorderRef.current) stopRecorder('error')
    stopAllTracks(streamRef.current)
    streamRef.current = null
    stopTick()
    cleanupAnalyser()
    setError({ kind, message })
    setElapsedMs(0)
    setLevel(0)
    setNearLimit(false)
    transition('ERROR')
  }, [cleanupAnalyser, stopTick, transition])

  /** 停止 MediaRecorder；onstop 到达时按 reason 决定是否产出 Blob（计划 §13.3）。 */
  const stopRecorder = useCallback((reason: StopReason) => {
    const recorder = recorderRef.current
    if (!recorder) return
    recorderRef.current = null
    const generation = generationRef.current
    recorder.onstop = () => {
      stopTick()
      cleanupAnalyser()
      const mime = mimeRef.current ?? 'audio/webm'
      const audio = new Blob(chunksRef.current, { type: mime })
      const resolve = stopResolveRef.current
      stopResolveRef.current = null
      const materialize = reason !== 'discard' && reason !== 'error'
      const fresh = generationRef.current === generation
      if (materialize && fresh && liveRef.current) {
        blobRef.current = audio
        setBlob(audio)
        const url = URL.createObjectURL(audio)
        blobUrlRef.current = url
        setBlobUrl(url)
        setElapsedMs(Math.min(elapsedNow(), maxMsRef.current))
        setNearLimit(false)
        if (reason === 'auto') {
          autoStoppedRef.current = true
          setAutoStopped(true)
        }
        transition('RECORDED')
      }
      resolve?.(audio)
    }
    recorder.stop()
  }, [cleanupAnalyser, elapsedNow, stopTick, transition])

  const setupAnalyser = useCallback((stream: MediaStream) => {
    cleanupAnalyser()
    if (!env.AudioContext) return
    try {
      const ctx = new env.AudioContext()
      const source = ctx.createMediaStreamSource(stream)
      const analyser = ctx.createAnalyser()
      analyser.fftSize = 512
      source.connect(analyser)
      analyserRef.current = { ctx, source, analyser, data: new Uint8Array(analyser.fftSize) }
    } catch {
      analyserRef.current = null // 分析节点不可用时电平降级为 0
    }
  }, [cleanupAnalyser, env])

  const tick = useCallback(() => {
    if (stateRef.current === 'RECORDING') {
      const elapsed = elapsedNow()
      setElapsedMs(Math.min(elapsed, maxMsRef.current))
      const remaining = maxMsRef.current - elapsed
      if (!nearLimitRef.current && remaining <= warnMsRef.current) {
        nearLimitRef.current = true
        setNearLimit(true)
      }
      if (remaining <= 0) {
        stopRecorder('auto')
        return
      }
      setLevel(readLevel())
    } else if (stateRef.current === 'PAUSED') {
      // 暂停时麦克风仍在采集，但电平展示为静止
      setLevel(0)
    }
  }, [elapsedNow, readLevel, stopRecorder])

  const startTick = useCallback(() => {
    stopTick()
    tickRef.current = window.setInterval(tick, tickMsRef.current)
  }, [stopTick, tick])

  const start = useCallback(async () => {
    if (inFlightRef.current) return
    // RECORDED 必须先 reset() 再重录（tracks 在 reset 时释放，见文件头设计决策 2）
    if (stateRef.current !== 'IDLE' && stateRef.current !== 'ERROR') return
    const capability = detectMediaRecorderCapabilities(env.MediaRecorder)
    if (!capability.supported) {
      // 先探测能力再请求权限：录音不可用时避免弹无意义的权限提示
      fail('UNAVAILABLE', ERROR_MESSAGES.UNAVAILABLE)
      return
    }
    inFlightRef.current = true
    setError(null)
    transition('REQUESTING_PERMISSION')
    try {
      const stream = await env.getUserMedia({ audio: true })
      if (!liveRef.current) {
        stopAllTracks(stream)
        return
      }
      streamRef.current = stream
      let recorder: RecorderLike
      try {
        recorder = new env.MediaRecorder!(stream, { mimeType: capability.mimeType ?? undefined }) as unknown as RecorderLike
      } catch {
        stopAllTracks(stream)
        streamRef.current = null
        fail('UNAVAILABLE', ERROR_MESSAGES.UNAVAILABLE)
        return
      }
      mimeRef.current = capability.mimeType
      chunksRef.current = []
      recorderRef.current = recorder
      recorder.ondataavailable = (event) => {
        if (event.data?.size) chunksRef.current.push(event.data)
      }
      recorder.onerror = () => {
        if (stateRef.current === 'RECORDING' || stateRef.current === 'PAUSED') {
          fail('UNKNOWN', ERROR_MESSAGES.UNKNOWN)
        }
      }
      setupAnalyser(stream)
      recorder.start()
      segmentStartRef.current = env.now()
      transition('RECORDING')
      startTick()
    } catch (reason) {
      if (!liveRef.current) return
      const kind = errorKindFor(reason)
      fail(kind, ERROR_MESSAGES[kind])
    } finally {
      inFlightRef.current = false
    }
  }, [env, fail, setupAnalyser, startTick, transition])

  const pause = useCallback(() => {
    if (stateRef.current !== 'RECORDING') return
    accumulatedMsRef.current += Math.max(0, env.now() - segmentStartRef.current)
    recorderRef.current?.pause()
    transition('PAUSED')
  }, [env, transition])

  const resume = useCallback(() => {
    if (stateRef.current !== 'PAUSED') return
    segmentStartRef.current = env.now()
    recorderRef.current?.resume()
    transition('RECORDING')
  }, [env, transition])

  const stop = useCallback((): Promise<Blob> => {
    if (stateRef.current === 'RECORDED' && blobRef.current) return Promise.resolve(blobRef.current)
    if (stateRef.current !== 'RECORDING' && stateRef.current !== 'PAUSED') {
      return Promise.reject(new Error('NOT_RECORDING'))
    }
    const pending = new Promise<Blob>((resolve) => {
      stopResolveRef.current = resolve
    })
    stopRecorder('manual')
    return pending
  }, [stopRecorder])

  const reset = useCallback(() => {
    generationRef.current += 1
    if (recorderRef.current) stopRecorder('discard')
    stopAllTracks(streamRef.current)
    streamRef.current = null
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current)
      blobUrlRef.current = null
    }
    chunksRef.current = []
    mimeRef.current = null
    accumulatedMsRef.current = 0
    segmentStartRef.current = 0
    nearLimitRef.current = false
    autoStoppedRef.current = false
    stopTick()
    cleanupAnalyser()
    setAutoStopped(false)
    setNearLimit(false)
    blobRef.current = null
    setBlob(null)
    setBlobUrl(null)
    setElapsedMs(0)
    setLevel(0)
    setError(null)
    transition('IDLE')
  }, [cleanupAnalyser, stopRecorder, stopTick, transition])

  // 卸载清理：停止所有 tracks（不留麦克风指示灯）、撤销 Object URL、断开分析节点；
  // 录音中卸载直接丢弃录音（见文件头设计决策 3）
  useEffect(() => () => {
    liveRef.current = false
    generationRef.current += 1
    if (recorderRef.current) stopRecorder('discard')
    stopAllTracks(streamRef.current)
    streamRef.current = null
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current)
      blobUrlRef.current = null
    }
    stopTick()
    cleanupAnalyser()
  }, [cleanupAnalyser, stopRecorder, stopTick])

  return useMemo<VoiceRecorderController>(() => ({
    state,
    elapsedMs,
    level,
    blob,
    blobUrl,
    nearLimit,
    autoStopped,
    unavailable,
    textFallback: error !== null && error.kind !== 'UNKNOWN',
    error,
    start,
    pause,
    resume,
    stop,
    reset,
  }), [autoStopped, blob, blobUrl, elapsedMs, error, level, nearLimit, pause, reset, resume, start, state, stop, unavailable])
}
