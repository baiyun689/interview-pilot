import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAccessToken } from '../api/request'
import { AUTOPLAY_STORAGE_KEY, QuestionSpeechPlayer } from './QuestionSpeechPlayer'

const MEDIA_URL = '/api/interviews/session-1/speech/speech-1/media'

function mediaResponse(blob: Blob) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'Content-Type': 'audio/webm' }),
    blob: vi.fn().mockResolvedValue(blob),
  } as unknown as Response
}

let playMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  localStorage.clear()
  setAccessToken('jwt-token')
  let counter = 0
  URL.createObjectURL = vi.fn(() => `blob:speech-${++counter}`)
  URL.revokeObjectURL = vi.fn()
  playMock = vi.fn(() => Promise.resolve())
  HTMLMediaElement.prototype.play = playMock as unknown as typeof HTMLMediaElement.prototype.play
  // jsdom 未实现 pause（卸载清理会调用），静音其“Not implemented”日志
  HTMLMediaElement.prototype.pause = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
})

async function renderReady(ui: React.ReactElement) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mediaResponse(new Blob(['audio'], { type: 'audio/webm' }))))
  const view = render(ui)
  const audio = await screen.findByLabelText('题目语音')
  // The audio node can appear before React flushes the effect registering canplay.
  await act(async () => {})
  return { view, audio }
}

describe('QuestionSpeechPlayer', () => {
  it('带认证头拉取媒体后以 Object URL 播放，就绪后自动播放一次', async () => {
    const { audio } = await renderReady(<QuestionSpeechPlayer mediaUrl={MEDIA_URL} />)

    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe(MEDIA_URL)
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer jwt-token')

    expect(audio).toHaveAttribute('src', 'blob:speech-1')
    fireEvent.canPlay(audio)
    fireEvent.canPlay(audio)
    expect(playMock).toHaveBeenCalledTimes(1)
  })

  it('localStorage 偏好关闭时不自动播放，展示明显的播放按钮', async () => {
    localStorage.setItem(AUTOPLAY_STORAGE_KEY, 'off')
    const { audio } = await renderReady(<QuestionSpeechPlayer mediaUrl={MEDIA_URL} />)

    fireEvent.canPlay(audio)
    expect(playMock).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '播放语音' })).toBeInTheDocument()
  })

  it('自动播放被浏览器阻止时回退到明显的播放按钮，点击后播放', async () => {
    playMock = vi.fn()
      .mockRejectedValueOnce(Object.assign(new Error('blocked'), { name: 'NotAllowedError' }))
      .mockResolvedValueOnce(undefined)
    HTMLMediaElement.prototype.play = playMock as unknown as typeof HTMLMediaElement.prototype.play

    const { audio } = await renderReady(<QuestionSpeechPlayer mediaUrl={MEDIA_URL} />)
    fireEvent.canPlay(audio)

    expect(playMock).toHaveBeenCalledTimes(1)
    const button = await screen.findByRole('button', { name: '播放语音' })
    fireEvent.click(button)
    await waitFor(() => expect(playMock).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('button', { name: '播放语音' })).not.toBeInTheDocument())
  })

  it('autoPlay 属性覆盖 localStorage 偏好', async () => {
    localStorage.setItem(AUTOPLAY_STORAGE_KEY, 'off')
    const { audio } = await renderReady(
      <QuestionSpeechPlayer mediaUrl={MEDIA_URL} autoPlay />,
    )
    fireEvent.canPlay(audio)
    expect(playMock).toHaveBeenCalledTimes(1)
  })

  it('mediaUrl 变化时撤销旧 Object URL 并加载新媒体', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(mediaResponse(new Blob(['a'], { type: 'audio/webm' })))
      .mockResolvedValueOnce(mediaResponse(new Blob(['b'], { type: 'audio/webm' })))
    vi.stubGlobal('fetch', fetchMock)

    const { rerender } = render(<QuestionSpeechPlayer mediaUrl={MEDIA_URL} />)
    const firstAudio = await screen.findByLabelText('题目语音')
    expect(firstAudio).toHaveAttribute('src', 'blob:speech-1')

    rerender(<QuestionSpeechPlayer mediaUrl="/api/interviews/session-1/speech/speech-2/media" />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByLabelText('题目语音')).toHaveAttribute('src', 'blob:speech-2'))
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:speech-1')
  })

  it('卸载时撤销 Object URL 并暂停播放', async () => {
    const { audio, view } = await renderReady(<QuestionSpeechPlayer mediaUrl={MEDIA_URL} />)
    expect(audio).toHaveAttribute('src', 'blob:speech-1')

    view.unmount()

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:speech-1')
  })

  it('媒体加载失败时展示错误并回调 onError', async () => {
    const onError = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      headers: new Headers({ 'Content-Type': 'application/json', 'X-Trace-Id': 't-1' }),
      text: vi.fn().mockResolvedValue(JSON.stringify({ code: 'QUESTION_SPEECH_NOT_READY', message: '语音尚未就绪' })),
    } as unknown as Response))

    render(<QuestionSpeechPlayer mediaUrl={MEDIA_URL} onError={onError} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('语音尚未就绪')
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ code: 'QUESTION_SPEECH_NOT_READY' }))
  })
})
