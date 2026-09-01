import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, onUnauthorized, setAccessToken } from './request'
import {
  discardVoiceRecording,
  fetchVoiceCapabilities,
  fetchVoiceMediaBlob,
  getQuestionSpeech,
  getVoiceRecording,
  retryQuestionSpeech,
  retryVoiceRecording,
  uploadVoiceRecording,
} from './voice'

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

class FakeXHR {
  static instances: FakeXHR[] = []
  open = vi.fn()
  setRequestHeader = vi.fn()
  send = vi.fn()
  abort = vi.fn(() => { this.onabort?.() })
  upload: { onprogress: ((event: { loaded: number; total: number; lengthComputable: boolean }) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  status = 0
  responseText = ''
  responseHeaders: Record<string, string> = {}
  getResponseHeader(name: string) {
    return this.responseHeaders[name] ?? null
  }
  constructor() {
    FakeXHR.instances.push(this)
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  setAccessToken(null)
  FakeXHR.instances = []
})

function lastXhr(): FakeXHR {
  return FakeXHR.instances[FakeXHR.instances.length - 1]
}

describe('voice api', () => {
  it('fetchVoiceCapabilities 请求 JWT 保护的 /api/voice/capabilities', async () => {
    setAccessToken('jwt-token')
    const fetchMock = vi.fn().mockResolvedValue(json({
      enabled: true,
      supportedMimeTypes: ['audio/webm', 'audio/ogg'],
      maxRecordingSeconds: 300,
      maxUploadBytes: 8388608,
      ttsEnabled: true,
    }))
    vi.stubGlobal('fetch', fetchMock)

    const capabilities = await fetchVoiceCapabilities()

    expect(capabilities).toMatchObject({ enabled: true, maxRecordingSeconds: 300, ttsEnabled: true })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/voice/capabilities')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer jwt-token')
  })

  it('uploadVoiceRecording 以 multipart 提交 uploadRequestId 与带扩展名的 audio 文件，并带认证头', async () => {
    setAccessToken('jwt-token')
    vi.stubGlobal('XMLHttpRequest', FakeXHR)

    const blob = new Blob(['fake-audio'], { type: 'audio/webm;codecs=opus' })
    const promise = uploadVoiceRecording('session-1', 3, 'req-abc', blob)

    const xhr = lastXhr()
    expect(xhr.open).toHaveBeenCalledWith('POST', '/api/interviews/session-1/turns/3/voice-recordings')
    expect(xhr.setRequestHeader).toHaveBeenCalledWith('Authorization', 'Bearer jwt-token')

    const form = xhr.send.mock.calls[0][0] as FormData
    expect(form).toBeInstanceOf(FormData)
    expect(form.get('uploadRequestId')).toBe('req-abc')
    const file = form.get('audio') as File
    expect(file).toBeInstanceOf(Blob)
    expect(file.name).toMatch(/\.webm$/)

    xhr.status = 202
    xhr.responseText = JSON.stringify({
      recordingId: 'rec-1',
      status: 'UPLOADED',
      transcriptionTaskId: 'task-1',
    })
    xhr.responseHeaders['X-Trace-Id'] = 'trace-1'
    xhr.onload?.()

    await expect(promise).resolves.toEqual({
      data: { recordingId: 'rec-1', status: 'UPLOADED', transcriptionTaskId: 'task-1' },
      status: 202,
      traceId: 'trace-1',
    })
  })

  it('上传按请求头进度回调（onProgress）', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const onProgress = vi.fn()
    const promise = uploadVoiceRecording('session-1', 1, 'req-1', new Blob(['x']), onProgress)

    lastXhr().upload.onprogress?.({ loaded: 10, total: 100, lengthComputable: true })
    expect(onProgress).toHaveBeenCalledWith({ loaded: 10, total: 100 })

    lastXhr().status = 202
    lastXhr().responseText = JSON.stringify({ recordingId: 'r', status: 'UPLOADED', transcriptionTaskId: null })
    lastXhr().onload?.()
    await promise
  })

  it('上传失败把后端错误 code 暴露为 ApiClientError', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const promise = uploadVoiceRecording('session-1', 1, 'req-1', new Blob(['x']))

    const xhr = lastXhr()
    xhr.status = 413
    xhr.responseText = JSON.stringify({ code: 'VOICE_UPLOAD_TOO_LARGE', message: '音频超过 8 MiB 限制' })
    xhr.onload?.()

    const failure = await promise.catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(ApiClientError)
    expect(failure).toMatchObject({ status: 413, code: 'VOICE_UPLOAD_TOO_LARGE', message: '音频超过 8 MiB 限制' })
  })

  it('上传网络失败返回安全的 NETWORK_ERROR', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const promise = uploadVoiceRecording('session-1', 1, 'req-1', new Blob(['x']))
    lastXhr().onerror?.()

    await expect(promise).rejects.toMatchObject({
      status: 0,
      code: 'NETWORK_ERROR',
      message: '网络连接失败，请检查网络后重试',
    })
  })

  it('上传请求被 AbortSignal 中止时抛出 AbortError', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const controller = new AbortController()
    const promise = uploadVoiceRecording('session-1', 1, 'req-1', new Blob(['x']), undefined, controller.signal)
    controller.abort()

    expect(lastXhr().abort).toHaveBeenCalled()
    const failure = await promise.catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(DOMException)
    expect((failure as DOMException).name).toBe('AbortError')
  })

  it('调用前已中止的 AbortSignal 直接拒绝，不发任何请求', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const controller = new AbortController()
    controller.abort()

    const failure = await uploadVoiceRecording(
      'session-1', 1, 'req-1', new Blob(['x']), undefined, controller.signal,
    ).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(DOMException)
    expect((failure as DOMException).name).toBe('AbortError')
    expect(FakeXHR.instances).toHaveLength(0)
  })

  it('上传 401 时刷新令牌并用新令牌重传一次（评审 I3）', async () => {
    setAccessToken('expired-token')
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const fetchMock = vi.fn().mockResolvedValue(json({ accessToken: 'fresh-token' }))
    vi.stubGlobal('fetch', fetchMock)

    const promise = uploadVoiceRecording('session-1', 1, 'req-1', new Blob(['x']))
    const first = lastXhr()
    first.status = 401
    first.responseText = JSON.stringify({ code: 'UNAUTHORIZED', message: 'token expired' })
    first.onload?.()

    await vi.waitFor(() => expect(FakeXHR.instances).toHaveLength(2))
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/refresh', expect.objectContaining({ method: 'POST' }))
    const second = lastXhr()
    expect(second.setRequestHeader).toHaveBeenCalledWith('Authorization', 'Bearer fresh-token')

    second.status = 202
    second.responseText = JSON.stringify({ recordingId: 'r', status: 'UPLOADED', transcriptionTaskId: null })
    second.onload?.()

    await expect(promise).resolves.toMatchObject({ status: 202, data: { recordingId: 'r' } })
  })

  it('刷新后重传仍 401 时通知未授权处理器并拒绝（评审 I3）', async () => {
    setAccessToken('stale-token')
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ accessToken: 'fresh-token' })))
    const handler = vi.fn()
    const unsubscribe = onUnauthorized(handler)

    const promise = uploadVoiceRecording('session-1', 1, 'req-1', new Blob(['x']))
    const first = lastXhr()
    first.status = 401
    first.responseText = JSON.stringify({ code: 'UNAUTHORIZED', message: 'token expired' })
    first.onload?.()

    await vi.waitFor(() => expect(FakeXHR.instances).toHaveLength(2))
    const second = lastXhr()
    second.status = 401
    second.responseText = JSON.stringify({ code: 'UNAUTHORIZED', message: 'still expired' })
    second.onload?.()

    const failure = await promise.catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(ApiClientError)
    expect((failure as ApiClientError).status).toBe(401)
    expect(handler).toHaveBeenCalled()
    unsubscribe()
  })

  it('getVoiceRecording / retry / discard 使用正确的路径与方法', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json({
      recordingId: 'rec-1', turnNo: 2, status: 'READY',
      rawTranscript: '转写文本', durationMillis: 3200, retryable: false, safeError: null,
    }))
    vi.stubGlobal('fetch', fetchMock)

    await getVoiceRecording('session-1', 'rec-1')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/interviews/session-1/voice-recordings/rec-1')

    await retryVoiceRecording('session-1', 'rec-1')
    expect(fetchMock.mock.calls[1]).toEqual([
      '/api/interviews/session-1/voice-recordings/rec-1/retry',
      expect.objectContaining({ method: 'POST' }),
    ])

    await discardVoiceRecording('session-1', 'rec-1')
    expect(fetchMock.mock.calls[2]).toEqual([
      '/api/interviews/session-1/voice-recordings/rec-1/discard',
      expect.objectContaining({ method: 'POST' }),
    ])
  })

  it('getQuestionSpeech 保留 202 状态以区分合成中与就绪', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(
      { speechId: null, status: 'SYNTHESIZING', mediaUrl: null, retryable: false, safeError: null },
      202,
    )))

    const response = await getQuestionSpeech('session-1', 3)
    expect(response.status).toBe(202)
    expect(response.data).toMatchObject({ status: 'SYNTHESIZING', speechId: null })
  })

  it('retryQuestionSpeech 使用 POST 并带上转写轮次', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 202 }))
    vi.stubGlobal('fetch', fetchMock)

    await retryQuestionSpeech('session-1', 3)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/interviews/session-1/turns/3/speech/retry')
  })

  it('fetchVoiceMediaBlob 携带认证头并以 Blob 返回媒体（媒体端点仅接受 Bearer 头）', async () => {
    setAccessToken('jwt-token')
    const blob = new Blob(['audio-bytes'], { type: 'audio/webm' })
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'audio/webm' }),
      blob: vi.fn().mockResolvedValue(blob),
    } as unknown as Response)
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchVoiceMediaBlob('/api/interviews/session-1/speech/speech-1/media')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/interviews/session-1/speech/speech-1/media')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer jwt-token')
    expect(result).toBeInstanceOf(Blob)
    expect(result.size).toBe('audio-bytes'.length)
  })

  it('fetchVoiceMediaBlob 把媒体错误 code 暴露为 ApiClientError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(
      { code: 'VOICE_RECORDING_NOT_FOUND', message: '录音不存在', traceId: 't-9' },
      404,
    )))

    const failure = await fetchVoiceMediaBlob('/media').catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(ApiClientError)
    expect(failure).toMatchObject({ status: 404, code: 'VOICE_RECORDING_NOT_FOUND', traceId: 't-9' })
  })
})
