import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClientError, request } from './request'

afterEach(() => vi.unstubAllGlobals())

describe('request', () => {
  it('为会改变状态的请求附加 CSRF token 和会话凭据', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123; path=/'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      headers: { 'Content-Type': 'application/json' },
    })))

    await request('/api/example', { method: 'POST', body: '{}' })

    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(init).toMatchObject({ credentials: 'include' })
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('csrf-123')
  })

  it('保留调用方为状态变更请求提供的 CSRF header', async () => {
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      headers: { 'Content-Type': 'application/json' },
    })))

    await request('/api/example', {
      method: 'PATCH',
      headers: { 'X-XSRF-TOKEN': 'caller-token' },
    })

    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('caller-token')
  })

  it('不为安全方法附加 CSRF token，但始终携带会话凭据', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-123; path=/'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      headers: { 'Content-Type': 'application/json' },
    })))

    await request('/api/example', { method: 'GET' })

    const [, init] = vi.mocked(fetch).mock.calls[0]
    expect(init).toMatchObject({ credentials: 'include' })
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBeNull()
  })

  it('将后端语义错误解析为带 HTTP 状态和 traceId 的客户端错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'PROVIDER_DISABLED',
      message: '模型未启用',
      traceId: 'trace-body',
    }), {
      status: 409,
      headers: {
        'Content-Type': 'application/json',
        'X-Trace-Id': 'trace-body',
      },
    })))

    const failure = await request('/api/example').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiClientError)
    expect(failure).toMatchObject({
      status: 409,
      code: 'PROVIDER_DISABLED',
      message: '模型未启用',
      traceId: 'trace-body',
    })
  })

  it('错误正文没有 traceId 时保留响应头中的关联 ID', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'BAD_REQUEST',
      message: '请求参数有误',
    }), {
      status: 400,
      headers: {
        'Content-Type': 'application/json',
        'X-Trace-Id': 'trace-header',
      },
    })))

    await expect(request('/api/example')).rejects.toMatchObject({ traceId: 'trace-header' })
  })

  it('正文与响应头的 traceId 不一致时保留响应头关联 ID', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'CONFLICT',
      message: '状态冲突',
      traceId: 'unexpected-body-trace',
    }), {
      status: 409,
      headers: {
        'Content-Type': 'application/json',
        'X-Trace-Id': 'trusted-header-trace',
      },
    })))

    await expect(request('/api/example')).rejects.toMatchObject({ traceId: 'trusted-header-trace' })
  })

  it('不会把 HTML 错误页暴露给用户，并仍保留 traceId', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<h1>proxy secret</h1>', {
      status: 502,
      headers: {
        'Content-Type': 'text/html',
        'X-Trace-Id': 'trace-proxy',
      },
    })))

    const failure = await request('/api/example').catch((error: unknown) => error)

    expect(failure).toMatchObject({
      status: 502,
      code: 'HTTP_ERROR',
      message: '服务暂时不可用，请稍后重试',
      traceId: 'trace-proxy',
    })
    expect(String(failure)).not.toContain('proxy secret')
  })

  it('网络失败时返回安全的中文错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch https://secret')))

    await expect(request('/api/example')).rejects.toMatchObject({
      status: 0,
      code: 'NETWORK_ERROR',
      message: '网络连接失败，请检查网络后重试',
      traceId: null,
    })
  })

  it('读取响应正文失败时抛出保留响应元数据的安全客户端错误', async () => {
    const response = {
      ok: false,
      status: 503,
      headers: new Headers({
        'Content-Type': 'text/html',
        'X-Trace-Id': 'trace-read-failure',
      }),
      text: vi.fn().mockRejectedValue(new Error('proxy response contained secret HTML')),
    } as unknown as Response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))

    const failure = await request('/api/example').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiClientError)
    expect(failure).toMatchObject({
      status: 503,
      code: 'RESPONSE_READ_ERROR',
      message: '读取服务响应失败，请稍后重试',
      traceId: 'trace-read-failure',
    })
    expect(String(failure)).not.toContain('secret HTML')
  })

  it('支持无正文的成功响应', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))

    await expect(request<void>('/api/example')).resolves.toBeUndefined()
  })
})
