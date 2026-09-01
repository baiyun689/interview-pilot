import { vi } from 'vitest'
import type { VoiceRecorderEnvironment } from '../voice/useVoiceRecorder'

/**
 * 录音相关测试假件：FakeMediaRecorder / FakeStream / FakeAudioContext 以及
 * 组装 useVoiceRecorder 注入环境（env）的工厂。测试不依赖真实浏览器 API。
 */

export class FakeTrack {
  stop = vi.fn()
}

export class FakeStream {
  track = new FakeTrack()
  getTracks = vi.fn(() => [this.track])
}

export class FakeMediaRecorder {
  static isTypeSupported = vi.fn((mime: string) => mime.startsWith('audio/webm'))
  static instances: FakeMediaRecorder[] = []
  /** 非 0 时 stop() 延迟触发 onstop（模拟真实浏览器异步 onstop，用于竞态测试）。 */
  static deferStopMs = 0
  state: 'inactive' | 'recording' | 'paused' = 'inactive'
  ondataavailable: ((event: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null
  onerror: ((event: unknown) => void) | null = null
  readonly options?: MediaRecorderOptions
  constructor(public stream: MediaStream, options?: MediaRecorderOptions) {
    this.options = options
    FakeMediaRecorder.instances.push(this)
  }
  start() { this.state = 'recording' }
  pause() { this.state = 'paused' }
  resume() { this.state = 'recording' }
  stop() {
    this.state = 'inactive'
    this.ondataavailable?.({
      data: new Blob(['fake-audio'], { type: this.options?.mimeType ?? 'audio/webm' }),
    })
    if (FakeMediaRecorder.deferStopMs > 0) {
      setTimeout(() => this.onstop?.(), FakeMediaRecorder.deferStopMs)
    } else {
      this.onstop?.()
    }
  }
}

export class FakeAnalyser {
  fftSize = 8
  disconnect = vi.fn()
  getByteTimeDomainData(array: Uint8Array) {
    array.fill(255)
  }
}

export class FakeAudioContext {
  static instances: FakeAudioContext[] = []
  analyser = new FakeAnalyser()
  createMediaStreamSource = vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() }))
  createAnalyser = vi.fn(() => this.analyser)
  close = vi.fn().mockResolvedValue(undefined)
  constructor() {
    FakeAudioContext.instances.push(this)
  }
}

export interface RecorderTestEnv {
  env: VoiceRecorderEnvironment
  stream: FakeStream
  getUserMedia: ReturnType<typeof vi.fn>
}

/** 组装一个可注入 useVoiceRecorder 的测试环境。 */
export function recorderTestEnv(overrides: Partial<VoiceRecorderEnvironment> = {}): RecorderTestEnv {
  const stream = new FakeStream()
  const getUserMedia = vi.fn().mockResolvedValue(stream as unknown as MediaStream)
  return {
    env: {
      getUserMedia,
      MediaRecorder: FakeMediaRecorder as unknown as typeof MediaRecorder,
      AudioContext: null,
      now: () => Date.now(),
      ...overrides,
    },
    stream,
    getUserMedia,
  }
}

export function lastRecorder(): FakeMediaRecorder {
  return FakeMediaRecorder.instances[FakeMediaRecorder.instances.length - 1]
}
