interface ApiErrorBody {
  code?: unknown
  message?: unknown
  traceId?: unknown
}

export class ApiClientError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | null

  constructor(status: number, code: string, message: string, traceId: string | null) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.code = code
    this.traceId = traceId
  }
}

function nonBlankString(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function correlationId(response: Response, body?: ApiErrorBody): string | null {
  const headerTraceId = nonBlankString(response.headers.get('X-Trace-Id'))
  const bodyTraceId = nonBlankString(body?.traceId)
  if (bodyTraceId && (!headerTraceId || bodyTraceId === headerTraceId)) return bodyTraceId
  return headerTraceId
}

function isJson(response: Response): boolean {
  const contentType = response.headers.get('Content-Type')?.toLowerCase() ?? ''
  return contentType.includes('application/json') || contentType.includes('+json')
}

async function safeJson(text: string): Promise<unknown> {
  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

export interface ApiResponse<T> {
  data: T
  status: number
  traceId: string | null
}

export async function requestWithMeta<T>(path: string, init?: RequestInit): Promise<ApiResponse<T>> {
  let response: Response
  try {
    response = await fetch(path, init)
  } catch {
    throw new ApiClientError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络后重试', null)
  }

  let text: string
  try {
    text = await response.text()
  } catch {
    throw new ApiClientError(
      response.status,
      'RESPONSE_READ_ERROR',
      '读取服务响应失败，请稍后重试',
      correlationId(response),
    )
  }
  if (!response.ok) {
    const parsed = isJson(response) && text ? await safeJson(text) : undefined
    const body = parsed && typeof parsed === 'object' ? parsed as ApiErrorBody : undefined
    const code = nonBlankString(body?.code) ?? 'HTTP_ERROR'
    const message = nonBlankString(body?.message) ?? '服务暂时不可用，请稍后重试'
    throw new ApiClientError(response.status, code, message, correlationId(response, body))
  }

  if (!text) return { data: undefined as T, status: response.status, traceId: correlationId(response) }
  const parsed = isJson(response) ? await safeJson(text) : undefined
  if (parsed === undefined) {
    throw new ApiClientError(
      response.status,
      'INVALID_RESPONSE',
      '服务返回了无法识别的数据，请稍后重试',
      correlationId(response),
    )
  }
  return { data: parsed as T, status: response.status, traceId: correlationId(response) }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  return (await requestWithMeta<T>(path, init)).data
}
