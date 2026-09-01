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
/** stop() 等待 onstop 的兜底超时；到期仍未触发则以已收 chunk 完成停止 */
const STOP_TIMEOUT_MS = 3_000

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

interface StopPending {
  promise: Promise<Blob>
  resolve: (blob: Blob) => void
  timer?: number
}

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
  /** 挂起的 stop 及其兜底定时器；onstop 到达或超时后清算 */
  const stopPendingRef = useRef<StopPending | null>(null)
  /** 本次录音的 stop 是否已清算（onstop/超时/丢弃只允许清算一次） */
  const stopFinalizedRef = useRef(false)
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

  /**
   * 清算一次停止：无论 onstop 正常到达、超时兜底还是 stop() 抛异常，都只会
   * 执行一次（stopFinalizedRef 防重）。按 reason 决定是否产出 Blob 状态（计划 §13.3）：
   * discard/error 不产出；manual/auto 在代数未过期且组件存活时产出。
   */
  const materializeStop = useCallback((reason: StopReason, generation: number) => {
    if (stopFinalizedRef.current) return
    // 评审 R1：迟到的 onstop/超时属于旧录音代数（reset 已清理并清算）时完整跳过，
    // 不得误杀新录音的 tick/analyser，也不能置位 finalized 阻塞下一次 stop
    if (generationRef.current !== generation) return
    stopFinalizedRef.current = true
    stopTick()
    cleanupAnalyser()
    const mime = mimeRef.current ?? 'audio/webm'
    const audio = new Blob(chunksRef.current, { type: mime })
    const pending = stopPendingRef.current
    stopPendingRef.current = null
    if (pending?.timer !== undefined) window.clearTimeout(pending.timer)
    const materialize = reason !== 'discard' && reason !== 'error'
    const fresh = liveRef.current
    if (materialize && fresh) {
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
    pending?.resolve(audio)
  }, [cleanupAnalyser, elapsedNow, stopTick, transition])

  /**
   * 停止 MediaRecorder。recorder.stop() 在 recorder 已失效（致命错误后）时抛
   * InvalidStateError——此时同步执行清算，保证 cleanup 与状态迁移必然发生。
   */
  const stopRecorder = useCallback((reason: StopReason, generation = generationRef.current) => {
    const recorder = recorderRef.current
    if (!recorder) return
    recorderRef.current = null
    recorder.onstop = () => materializeStop(reason, generation)
    try {
      recorder.stop()
    } catch {
      materializeStop(reason, generation)
    }
  }, [materializeStop])

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
    // 记录发起时的代数：请求权限期间若发生 reset/卸载，代数的变化会让本次
    // start 在授权落地后放弃（评审 I2：start → reset → 授权 → 麦克风保持开启）
    const generation = generationRef.current
    inFlightRef.current = true
    setError(null)
    transition('REQUESTING_PERMISSION')
    try {
      const stream = await env.getUserMedia({ audio: true })
      if (!liveRef.current || generationRef.current !== generation) {
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
      if (!liveRef.current || generationRef.current !== generation) {
        // 构造成功的极短窗口内仍可能被 reset/卸载追上：停止 tracks 并丢弃未启动的 recorder
        stopAllTracks(stream)
        streamRef.current = null
        return
      }
      mimeRef.current = capability.mimeType
      chunksRef.current = []
      stopFinalizedRef.current = false
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
    const existing = stopPendingRef.current
    if (existing) return existing.promise // 评审 M1：重复 stop 返回同一挂起 Promise
    const generation = generationRef.current
    const entry = {} as StopPending
    entry.promise = new Promise<Blob>((resolve) => { entry.resolve = resolve })
    stopPendingRef.current = entry
    // 评审 M2：onstop 丢失时超时兜底，用已收 chunk 完成停止
    entry.timer = window.setTimeout(() => {
      if (stopPendingRef.current !== entry) return
      materializeStop('manual', generation)
    }, STOP_TIMEOUT_MS)
    stopRecorder('manual', generation)
    return entry.promise
  }, [materializeStop, stopRecorder])

  const reset = useCallback(() => {
    generationRef.current += 1
    if (recorderRef.current) stopRecorder('discard')
    // 评审 R1：清算挂起的 stop（兑现其 Promise 并清除超时定时器），
    // 否则迟到的 onstop/超时会在重录后误杀新录音的 tick/analyser
    if (stopPendingRef.current) materializeStop('discard', generationRef.current)
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
  }, [cleanupAnalyser, materializeStop, stopRecorder, stopTick, transition])

  // 卸载清理：停止所有 tracks（不留麦克风指示灯）、撤销 Object URL、断开分析节点；
  // 录音中卸载直接丢弃录音（见文件头设计决策 3）。
  // StrictMode（开发模式）会执行 effect → cleanup → effect 且保留 refs，因此必须
  // 在 effect 主体恢复 liveRef，否则 cleanup 置 false 后 start() 将永远卡在
  // REQUESTING_PERMISSION（评审 C1）。
  useEffect(() => {
    liveRef.current = true
    return () => {
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
    }
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
