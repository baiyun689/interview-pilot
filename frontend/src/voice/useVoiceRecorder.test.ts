import { createElement, StrictMode } from 'react'
import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  FakeAudioContext,
  FakeMediaRecorder,
  lastRecorder,
  recorderTestEnv,
  type RecorderTestEnv,
} from '../test/voiceFakes'
import { useVoiceRecorder } from './useVoiceRecorder'

beforeEach(() => {
  vi.useFakeTimers()
  FakeMediaRecorder.instances = []
  FakeMediaRecorder.deferStopMs = 0
  FakeMediaRecorder.suppressStop = false
  FakeAudioContext.instances = []
  URL.createObjectURL = vi.fn(() => 'blob:mock-url')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => {
  vi.useRealTimers()
})

async function resolvedStream(fake: RecorderTestEnv) {
  return await fake.getUserMedia.mock.results[0].value as unknown as typeof fake.stream
}

describe('useVoiceRecorder', () => {
  it('start 请求麦克风权限后进入 RECORDING，并以探测到的 MIME 构造 MediaRecorder', async () => {
    const fake = recorderTestEnv()
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))

    expect(result.current.state).toBe('IDLE')
    await act(async () => { await result.current.start() })

    expect(result.current.state).toBe('RECORDING')
    expect(fake.getUserMedia).toHaveBeenCalledWith({ audio: true })
    const recorder = lastRecorder()
    expect(recorder.options?.mimeType).toBe('audio/webm;codecs=opus')
    expect(recorder.state).toBe('recording')
  })

  it('start 在录音进行中不会重复请求权限', async () => {
    const fake = recorderTestEnv()
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))
    await act(async () => { await result.current.start() })
    await act(async () => { await result.current.start() })

    expect(result.current.state).toBe('RECORDING')
    expect(fake.getUserMedia).toHaveBeenCalledTimes(1)
  })

  it('权限被拒绝时进入 ERROR 并给出文字回退信号', async () => {
    const fake = recorderTestEnv({
      getUserMedia: vi.fn().mockRejectedValue(
        Object.assign(new Error('permission denied'), { name: 'NotAllowedError' }),
      ),
    })
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))

    await act(async () => { await result.current.start() })

    expect(result.current.state).toBe('ERROR')
    expect(result.current.error?.kind).toBe('PERMISSION_DENIED')
    expect(result.current.textFallback).toBe(true)
    expect(FakeMediaRecorder.instances).toHaveLength(0)
  })

  it('浏览器不支持录音（无 MediaRecorder）时标记 unavailable 且不请求权限', async () => {
    const fake = recorderTestEnv({ MediaRecorder: null })
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))

    expect(result.current.unavailable).toBe(true)
    await act(async () => { await result.current.start() })

    expect(result.current.state).toBe('ERROR')
    expect(result.current.error?.kind).toBe('UNAVAILABLE')
    expect(result.current.textFallback).toBe(true)
    expect(fake.getUserMedia).not.toHaveBeenCalled()
  })

  it('pause/resume：计时只在录音中累计，暂停时长不计入', async () => {
    const { result } = renderHook(() => useVoiceRecorder({ env: recorderTestEnv().env }))
    await act(async () => { await result.current.start() })

    act(() => { vi.advanceTimersByTime(1_000) })
    expect(result.current.elapsedMs).toBeGreaterThanOrEqual(1_000)

    act(() => { result.current.pause() })
    expect(result.current.state).toBe('PAUSED')
    act(() => { vi.advanceTimersByTime(5_000) })
    expect(result.current.elapsedMs).toBeGreaterThanOrEqual(1_000)
    expect(result.current.elapsedMs).toBeLessThan(2_000)

    act(() => { result.current.resume() })
    expect(result.current.state).toBe('RECORDING')
    act(() => { vi.advanceTimersByTime(1_000) })
    expect(result.current.elapsedMs).toBeGreaterThanOrEqual(2_000)
  })

  it('stop 返回带正确 MIME 的 Blob 并进入 RECORDED，tracks 保持存活以便重录', async () => {
    const fake = recorderTestEnv()
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))
    await act(async () => { await result.current.start() })
    act(() => { vi.advanceTimersByTime(1_500) })

    await act(async () => { await result.current.stop() })

    expect(result.current.state).toBe('RECORDED')
    const blob = result.current.blob
    expect(blob?.type).toBe('audio/webm;codecs=opus')
    expect(result.current.blobUrl).toBe('blob:mock-url')
    expect(result.current.elapsedMs).toBeGreaterThanOrEqual(1_500)
    const stream = await resolvedStream(fake)
    expect(stream.track.stop).not.toHaveBeenCalled()
  })

  it('reset 停止 tracks、撤销 Object URL 并回到 IDLE', async () => {
    const fake = recorderTestEnv()
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))
    await act(async () => { await result.current.start() })
    await act(async () => { await result.current.stop() })
    act(() => { result.current.reset() })

    expect(result.current.state).toBe('IDLE')
    expect(result.current.blob).toBeNull()
    expect(result.current.blobUrl).toBeNull()
    expect(result.current.elapsedMs).toBe(0)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
    const stream = await resolvedStream(fake)
    expect(stream.track.stop).toHaveBeenCalled()
  })

  it('录音中卸载：停止 tracks、丢弃录音，不产生 Blob', async () => {
    const fake = recorderTestEnv()
    const { result, unmount } = renderHook(() => useVoiceRecorder({ env: fake.env }))
    await act(async () => { await result.current.start() })
    const recorder = lastRecorder()

    unmount()

    const stream = await resolvedStream(fake)
    expect(stream.track.stop).toHaveBeenCalled()
    expect(recorder.state).toBe('inactive')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('超过最大时长自动停止并保留 Blob（autoStopped 置位，时长封顶）', async () => {
    const { result } = renderHook(() => useVoiceRecorder({
      env: recorderTestEnv().env,
      maxRecordingSeconds: 5,
    }))

    await act(async () => { await result.current.start() })
    act(() => { vi.advanceTimersByTime(250) })
    expect(result.current.nearLimit).toBe(true)
    act(() => { vi.advanceTimersByTime(5_500) })

    expect(result.current.state).toBe('RECORDED')
    expect(result.current.autoStopped).toBe(true)
    expect(result.current.blob).not.toBeNull()
    expect(result.current.elapsedMs).toBeLessThanOrEqual(5_000)
  })

  it('4:50（剩余 10 秒）触发 nearLimit 警告，5:00 自动停止', async () => {
    const { result } = renderHook(() => useVoiceRecorder({ env: recorderTestEnv().env }))
    await act(async () => { await result.current.start() })

    act(() => { vi.advanceTimersByTime(289_000) })
    expect(result.current.nearLimit).toBe(false)
    act(() => { vi.advanceTimersByTime(1_200) })
    expect(result.current.nearLimit).toBe(true)
    act(() => { vi.advanceTimersByTime(10_000) })
    expect(result.current.state).toBe('RECORDED')
    expect(result.current.autoStopped).toBe(true)
    expect(result.current.blob).not.toBeNull()
  })

  it('level 从 AnalyserNode 读取；无 AudioContext 时降级为 0', async () => {
    const plain = recorderTestEnv()
    const { result: without } = renderHook(() => useVoiceRecorder({ env: plain.env }))
    await act(async () => { await without.current.start() })
    act(() => { vi.advanceTimersByTime(400) })
    expect(without.current.level).toBe(0)

    const withAnalyser = recorderTestEnv({
      AudioContext: FakeAudioContext as unknown as typeof AudioContext,
    })
    const { result: withCtx } = renderHook(() => useVoiceRecorder({ env: withAnalyser.env }))
    await act(async () => { await withCtx.current.start() })
    act(() => { vi.advanceTimersByTime(400) })
    expect(withCtx.current.level).toBeGreaterThan(0.9)

    await act(async () => { await withCtx.current.stop() })
    const context = FakeAudioContext.instances[0]
    expect(context.close).toHaveBeenCalled()
  })

  it('stop 在未录音时拒绝', async () => {
    const { result } = renderHook(() => useVoiceRecorder({ env: recorderTestEnv().env }))
    const failure = await result.current.stop().catch((error: unknown) => error)
    expect((failure as Error).message).toBe('NOT_RECORDING')
  })

  it('stop 后立即 reset（onstop 异步到达）时丢弃过期录音，不产生 Blob 状态', async () => {
    FakeMediaRecorder.deferStopMs = 500
    const { result } = renderHook(() => useVoiceRecorder({ env: recorderTestEnv().env }))
    await act(async () => { await result.current.start() })

    let stopResult: Blob | Error | null = null
    let pending!: Promise<Blob>
    act(() => { pending = result.current.stop() })
    pending.then((blob) => { stopResult = blob }).catch((error: unknown) => { stopResult = error as Error })
    act(() => { result.current.reset() })
    act(() => { vi.advanceTimersByTime(1_000) })

    expect(result.current.state).toBe('IDLE')
    expect(result.current.blob).toBeNull()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
    await act(async () => { await pending })
    expect(stopResult).toBeInstanceOf(Blob)
  })

  // 评审 C1：StrictMode 下 React 18 开发模式执行 effect → cleanup → effect（refs 保留），
  // cleanup 把 liveRef 置 false；若 mount effect 不恢复，start() 会卡死在 REQUESTING_PERMISSION
  it('StrictMode 挂载（效应清理后重跑）后 start 正常进入 RECORDING', async () => {
    const fake = recorderTestEnv()
    const wrapper = ({ children }: { children: ReactNode }) => createElement(StrictMode, null, children)
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }), { wrapper })

    await act(async () => { await result.current.start() })

    expect(result.current.state).toBe('RECORDING')
    expect(fake.getUserMedia).toHaveBeenCalledTimes(1)
    expect(lastRecorder().state).toBe('recording')
  })

  // 评审 I1：致命错误后 recorder 已处于 inactive，stop() 抛 InvalidStateError；
  // 清理与状态迁移仍必须发生（否则麦克风常亮、stop() 永不兑现）
  it('onerror 时 recorder 已失效（stop 抛异常）仍完成清理并进入 ERROR', async () => {
    const fake = recorderTestEnv()
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))
    await act(async () => { await result.current.start() })

    const recorder = lastRecorder()
    recorder.state = 'inactive' // 模拟 fatal error 后 recorder 已死
    act(() => { recorder.onerror?.({}) })

    expect(result.current.state).toBe('ERROR')
    expect(result.current.error?.kind).toBe('UNKNOWN')
    const stream = await resolvedStream(fake)
    expect(stream.track.stop).toHaveBeenCalled()
  })

  // 评审 I2：start → reset → 权限授予后不得进入 RECORDING，麦克风必须被停止
  it('start 进行中 reset：权限授予后放弃启动并停止 tracks', async () => {
    let grant!: (stream: MediaStream) => void
    const fake = recorderTestEnv({
      getUserMedia: vi.fn(() => new Promise<MediaStream>((resolve) => { grant = resolve })),
    })
    const { result } = renderHook(() => useVoiceRecorder({ env: fake.env }))

    let pending!: Promise<void>
    act(() => { pending = result.current.start() })
    expect(result.current.state).toBe('REQUESTING_PERMISSION')
    act(() => { result.current.reset() })
    expect(result.current.state).toBe('IDLE')

    await act(async () => {
      grant(fake.stream as unknown as MediaStream)
      await pending
    })

    expect(result.current.state).toBe('IDLE')
    expect(fake.stream.track.stop).toHaveBeenCalled()
    expect(FakeMediaRecorder.instances).toHaveLength(0)
  })

  // 评审 M1：重复 stop 返回同一个挂起 Promise，不产生第二次 finalize
  it('重复 stop 返回同一 Promise，且只 finalize 一次', async () => {
    FakeMediaRecorder.deferStopMs = 500
    const { result } = renderHook(() => useVoiceRecorder({ env: recorderTestEnv().env }))
    await act(async () => { await result.current.start() })

    let first!: Promise<Blob>
    let second!: Promise<Blob>
    act(() => { first = result.current.stop() })
    act(() => { second = result.current.stop() })
    expect(second).toBe(first)

    act(() => { vi.advanceTimersByTime(1_000) })
    await act(async () => { await first })
    expect(result.current.state).toBe('RECORDED')
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
  })

  // 评审 M2：onstop 丢失时超时兜底，用已收 chunk 产出 Blob 并进入 RECORDED
  it('onstop 不触发时超时兜底产出 Blob', async () => {
    FakeMediaRecorder.suppressStop = true
    const { result } = renderHook(() => useVoiceRecorder({ env: recorderTestEnv().env }))
    await act(async () => { await result.current.start() })
    act(() => { vi.advanceTimersByTime(1_000) })

    let pending!: Promise<Blob>
    act(() => { pending = result.current.stop() })
    act(() => { vi.advanceTimersByTime(3_200) })
    await act(async () => { await pending })

    expect(result.current.state).toBe('RECORDED')
    expect(result.current.blob).not.toBeNull()
    expect(result.current.blobUrl).toBe('blob:mock-url')
    expect(result.current.elapsedMs).toBeGreaterThanOrEqual(1_000)
  })
})
