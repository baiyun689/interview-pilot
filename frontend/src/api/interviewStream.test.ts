import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, setAccessToken } from './request'
import { postInterviewAnswerStream } from './interviewStream'

afterEach(() => {
  setAccessToken(null)
})

const encoder = new TextEncoder()

function streamResponse(chunks: string[], status = 200, headers: Record<string, string> = {}) {
  return new Response(new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  }), { status, headers: { 'Content-Type': 'text/event-stream', ...headers } })
}

describe('POST SSE client', () => {
  it('injects Bearer token into the answer stream request', async () => {
    setAccessToken('jwt-stream-token')
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([]))
    vi.stubGlobal('fetch', fetchMock)

    await postInterviewAnswerStream('s1', { requestId: 'r1', answer: 'answer' }, { onEvent: vi.fn() })

    const [, init] = fetchMock.mock.calls[0]
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer jwt-stream-token')
  })

  it('parses split CRLF chunks, named events, multiline data, and final buffered events', async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamResponse([
      'event: FEED',
      'BACK\r\ndata: {"type":"FEEDBACK",\r\n',
      'data: "sessionId":"s1","turnNo":1,"payload":{"feedback":"ok"}}\r\n\r\n',
      'event: COMPLETED\ndata: {"type":"COMPLETED","sessionId":"s1","turnNo":1,"payload":{"status":"EVALUATING"}}',
    ]))
    vi.stubGlobal('fetch', fetchMock)
    const received: string[] = []

    await postInterviewAnswerStream('s1', { requestId: 'r1', answer: 'answer' }, {
      onEvent: (name, event) => received.push(`${name}:${event.type}`),
    })

    expect(received).toEqual(['FEEDBACK:FEEDBACK', 'COMPLETED:COMPLETED'])
  })

  it('turns malformed event JSON into a safe typed transport error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamResponse(['event: ERROR\ndata: {broken}\n\n'])))

    await expect(postInterviewAnswerStream('s1', { requestId: 'r1', answer: 'a' }, { onEvent: vi.fn() }))
      .rejects.toMatchObject({ code: 'INVALID_STREAM_EVENT', message: '服务返回了无法识别的流式数据' })
  })

  it('preserves status and trace id for non-2xx responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 'RATE_LIMITED', message: '请求过快', traceId: 'trace-stream' }),
      { status: 429, headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-stream' } },
    )))

    await expect(postInterviewAnswerStream('s1', { requestId: 'r1', answer: 'a' }, { onEvent: vi.fn() }))
      .rejects.toEqual(new ApiClientError(429, 'RATE_LIMITED', '请求过快', 'trace-stream'))
  })

  it('propagates AbortError on route cleanup', async () => {
    const controller = new AbortController()
    vi.stubGlobal('fetch', vi.fn((_url, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))
    const promise = postInterviewAnswerStream('s1', { requestId: 'r1', answer: 'a' }, {
      signal: controller.signal,
      onEvent: vi.fn(),
    })
    controller.abort()
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('sanitizes a response-body disconnect into a typed transport error', async () => {
    const body = new ReadableStream({ start(controller) { controller.error(new TypeError('socket detail')) } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })))

    await expect(postInterviewAnswerStream('s1', { requestId: 'r1', answer: 'a' }, { onEvent: vi.fn() }))
      .rejects.toMatchObject({ code: 'STREAM_DISCONNECTED', message: '连接中断，请确认恢复状态后重试' })
  })
})
