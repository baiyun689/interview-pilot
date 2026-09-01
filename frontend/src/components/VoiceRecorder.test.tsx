import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakeAudioContext, FakeMediaRecorder, recorderTestEnv } from '../test/voiceFakes'
import { VoiceRecorder } from './VoiceRecorder'

beforeEach(() => {
  vi.useFakeTimers()
  FakeMediaRecorder.instances = []
  FakeMediaRecorder.deferStopMs = 0
  FakeAudioContext.instances = []
  URL.createObjectURL = vi.fn(() => 'blob:mock-url')
  URL.revokeObjectURL = vi.fn()
})

afterEach(() => {
  vi.useRealTimers()
})

async function renderRecording(extraProps: Partial<React.ComponentProps<typeof VoiceRecorder>> = {}) {
  const fake = recorderTestEnv()
  const view = render(<VoiceRecorder env={fake.env} {...extraProps} />)
  fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
  await act(async () => { await vi.advanceTimersByTimeAsync(100) })
  return { view, fake }
}

describe('VoiceRecorder', () => {
  it('IDLE 显示开始录音按钮，点击后展示计时、电平与暂停/停止按钮', async () => {
    const fake = recorderTestEnv()
    render(<VoiceRecorder env={fake.env} />)
    const start = screen.getByRole('button', { name: '开始录音' })
    fireEvent.click(start)
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })

    expect(fake.getUserMedia).toHaveBeenCalledWith({ audio: true })
    expect(start).not.toBeInTheDocument()
    expect(screen.getByRole('timer', { name: '录音时长' })).toHaveTextContent('00:00')
    expect(screen.getByRole('meter', { name: '音量电平' })).toHaveAttribute('aria-valuenow', '0')
    expect(screen.getByRole('button', { name: '暂停录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '停止录音' })).toBeInTheDocument()

    act(() => { vi.advanceTimersByTime(1_000) })
    expect(screen.getByRole('timer', { name: '录音时长' })).toHaveTextContent('00:01')
  })

  it('环境不支持录音时提示改用文字输入且不显示录音按钮', async () => {
    const fake = recorderTestEnv({ MediaRecorder: null })
    render(<VoiceRecorder env={fake.env} onTextFallback={vi.fn()} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/不支持语音录音/)
    expect(screen.queryByRole('button', { name: '开始录音' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改用文字输入' })).toBeInTheDocument()
  })

  it('暂停/继续切换按钮并保持计时', async () => {
    await renderRecording()
    act(() => { vi.advanceTimersByTime(1_000) })

    fireEvent.click(screen.getByRole('button', { name: '暂停录音' }))
    expect(screen.getByRole('button', { name: '继续录音' })).toBeInTheDocument()
    act(() => { vi.advanceTimersByTime(5_000) })
    expect(screen.getByRole('timer', { name: '录音时长' })).toHaveTextContent('00:01')

    fireEvent.click(screen.getByRole('button', { name: '继续录音' }))
    act(() => { vi.advanceTimersByTime(1_000) })
    expect(screen.getByRole('timer', { name: '录音时长' })).toHaveTextContent('00:02')
  })

  it('停止后进入 RECORDED：可试听、可重录，重录回到 IDLE', async () => {
    const onUpload = vi.fn()
    await renderRecording({ onUpload })
    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))

    expect(screen.getByLabelText('录音试听')).toHaveAttribute('src', 'blob:mock-url')
    expect(screen.getByRole('button', { name: '上传录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新录音' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '上传录音' }))
    expect(onUpload).toHaveBeenCalledWith(expect.any(Blob))
    expect(screen.getByRole('button', { name: '上传录音' })).not.toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: '重新录音' }))
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()
  })

  it('uploading 时禁用上传按钮并显示上传中', async () => {
    const fake = recorderTestEnv()
    const { rerender } = render(<VoiceRecorder env={fake.env} onUpload={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })
    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))

    rerender(<VoiceRecorder env={fake.env} uploading onUpload={vi.fn()} />)
    expect(screen.getByRole('button', { name: '上传录音' })).toBeDisabled()
    expect(screen.getByText('上传中…')).toBeInTheDocument()
  })

  it('nearLimit 时显示自动停止警告', async () => {
    const fake = recorderTestEnv()
    render(<VoiceRecorder env={fake.env} maxRecordingSeconds={12} />)
    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    // 默认警告窗口 10 秒：12 秒上限下录音满 2 秒即触发
    await act(async () => { await vi.advanceTimersByTimeAsync(2_500) })

    expect(screen.getByRole('alert')).toHaveTextContent(/自动停止/)
  })

  it('到达最大时长自动停止并显示自动停止提示', async () => {
    const fake = recorderTestEnv()
    render(<VoiceRecorder env={fake.env} maxRecordingSeconds={12} />)
    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(12_500) })

    expect(screen.getByText(/录音已自动停止/)).toBeInTheDocument()
    expect(screen.getByLabelText('录音试听')).toHaveAttribute('src', 'blob:mock-url')
  })

  it('权限被拒时展示说明与文字回退按钮', async () => {
    const fake = recorderTestEnv({
      getUserMedia: vi.fn().mockRejectedValue(
        Object.assign(new Error('denied'), { name: 'NotAllowedError' }),
      ),
    })
    const onTextFallback = vi.fn()
    render(<VoiceRecorder env={fake.env} onTextFallback={onTextFallback} />)

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(50) })

    expect(screen.getByRole('alert')).toHaveTextContent(/麦克风权限/)
    fireEvent.click(screen.getByRole('button', { name: '改用文字输入' }))
    expect(onTextFallback).toHaveBeenCalled()
  })

  it('音量电平来自 AnalyserNode 并以百分比展示', async () => {
    const fake = recorderTestEnv({
      AudioContext: FakeAudioContext as unknown as typeof AudioContext,
    })
    render(<VoiceRecorder env={fake.env} />)
    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(400) })

    const meter = screen.getByRole('meter', { name: '音量电平' })
    // 255 采样值 → RMS 0.992 → 99%
    expect(meter).toHaveAttribute('aria-valuenow', '99')
  })

  it('uploadError 以错误提示展示', async () => {
    const fake = recorderTestEnv()
    const { rerender } = render(<VoiceRecorder env={fake.env} onUpload={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })
    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))

    rerender(<VoiceRecorder env={fake.env} uploadError={new Error('上传失败，请重试')} onUpload={vi.fn()} />)
    expect(screen.getByRole('alert')).toHaveTextContent('上传失败，请重试')
  })
})
