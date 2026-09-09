import { ApiClientError, fetchWithAuthRetry } from './request'
import type { InputMode, InterviewStreamEvent } from '../types/interview'

/** 答案提交载荷：语音答案携带 inputMode=VOICE 与 recordingId（后端据此绑定录音）。 */
export interface AnswerStreamInput {
  requestId: string
  answer: string
  inputMode?: InputMode
  recordingId?: string
  expectedTurnNo?: number
  sessionVersion?: number
}

interface StreamOptions {
  signal?: AbortSignal
  onEvent: (name: string, event: InterviewStreamEvent) => void
}

function safeText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function traceId(response: Response, body?: Record<string, unknown>): string | null {
  return safeText(response.headers.get('X-Trace-Id')) ?? safeText(body?.traceId)
}

async function httpError(response: Response): Promise<ApiClientError> {
  let body: Record<string, unknown> | undefined
  try {
    const parsed = JSON.parse(await response.text()) as unknown
    if (parsed && typeof parsed === 'object') body = parsed as Record<string, unknown>
  } catch { /* sanitized fallback */ }
  return new ApiClientError(response.status, safeText(body?.code) ?? 'HTTP_ERROR', safeText(body?.message) ?? '服务暂时不可用，请稍后重试', traceId(response, body))
}

export async function postInterviewAnswerStream(
  sessionId: string,
  input: AnswerStreamInput,
  options: StreamOptions,
): Promise<void> {
  let response: Response
  try {
    response = await fetchWithAuthRetry(`/api/interviews/${encodeURIComponent(sessionId)}/answers/stream`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(input), signal: options.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiClientError(0, 'STREAM_DISCONNECTED', '连接中断，请确认恢复状态后重试', null)
  }
  if (!response.ok) {
    throw await httpError(response)
  }
  if (!response.body) throw new ApiClientError(response.status, 'EMPTY_STREAM', '服务未返回流式数据', traceId(response))

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const emit = (block: string) => {
    const lines = block.split(/\r?\n/)
    let name = 'message'
    const data: string[] = []
    for (const line of lines) {
      if (line.startsWith('event:')) name = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
    }
    if (!data.length) return
    let event: InterviewStreamEvent
    try { event = JSON.parse(data.join('\n')) as InterviewStreamEvent }
    catch { throw new ApiClientError(response.status, 'INVALID_STREAM_EVENT', '服务返回了无法识别的流式数据', traceId(response)) }
    options.onEvent(name, event)
  }
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const normalized = buffer.replace(/\r\n/g, '\n')
      const blocks = normalized.split('\n\n')
      buffer = blocks.pop() ?? ''
      blocks.forEach(emit)
    }
    buffer += decoder.decode()
    if (buffer.trim()) emit(buffer)
  } catch (error) {
    if (error instanceof ApiClientError || (error instanceof DOMException && error.name === 'AbortError')) throw error
    throw new ApiClientError(response.status, 'STREAM_DISCONNECTED', '连接中断，请确认恢复状态后重试', traceId(response))
  } finally {
    reader.releaseLock()
  }
}
