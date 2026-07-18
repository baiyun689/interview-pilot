import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ModelSettingsPage } from './ModelSettingsPage'

const providers = [
  {
    id: 'openai',
    displayName: 'OpenAI',
    model: 'gpt-5-mini',
    enabled: true,
    defaultProvider: true,
  },
  {
    id: 'deepseek',
    displayName: 'DeepSeek',
    model: 'deepseek-chat',
    enabled: true,
    defaultProvider: false,
  },
]

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => vi.unstubAllGlobals())

describe('模型设置', () => {
  it('加载预配置模型，测试连接，并只在切换请求完成后更新默认模型', async () => {
    let resolveSwitch!: (response: Response) => void
    const switchResponse = new Promise<Response>((resolve) => {
      resolveSwitch = resolve
    })
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/ai/providers' && !init?.method) return Promise.resolve(jsonResponse(providers))
      if (url.endsWith('/deepseek/test')) {
        return Promise.resolve(jsonResponse({ success: true, latency: 'PT0.2S', error: null }))
      }
      if (url === '/api/ai/providers/default') return switchResponse
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<ModelSettingsPage />)

    const deepSeekCard = await screen.findByRole('article', { name: 'DeepSeek' })
    expect(screen.queryByLabelText(/API Key/i)).not.toBeInTheDocument()
    expect(within(deepSeekCard).queryByText('当前默认模型')).not.toBeInTheDocument()

    await user.click(within(deepSeekCard).getByRole('button', { name: '测试连接' }))
    expect(await within(deepSeekCard).findByText('连接成功')).toBeInTheDocument()

    await user.click(within(deepSeekCard).getByRole('button', { name: '设为默认' }))
    expect(within(deepSeekCard).queryByText('当前默认模型')).not.toBeInTheDocument()
    expect(within(deepSeekCard).getByRole('button', { name: '正在设置' })).toBeDisabled()

    resolveSwitch(jsonResponse({
      ...providers[1],
      displayName: 'DeepSeek（服务端确认）',
      model: 'deepseek-v4',
      defaultProvider: true,
    }))
    expect(await within(deepSeekCard).findByText('当前默认模型')).toBeInTheDocument()
    expect(within(deepSeekCard).getByText('DeepSeek（服务端确认）')).toBeInTheDocument()
    expect(within(deepSeekCard).getByText('deepseek-v4')).toBeInTheDocument()
    expect(screen.getAllByText('当前默认模型')).toHaveLength(1)
  })

  it('加载时显示状态，空列表返回明确提示', async () => {
    let resolveList!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>((resolve) => {
      resolveList = resolve
    })))

    render(<ModelSettingsPage />)

    expect(screen.getByRole('status')).toHaveTextContent('正在加载模型配置')
    resolveList(jsonResponse([]))
    expect(await screen.findByText('暂无可用的模型配置')).toBeInTheDocument()
  })

  it('加载失败时显示安全信息和 traceId，并允许重试', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: 'SERVICE_UNAVAILABLE',
        message: '模型服务暂时不可用',
        traceId: 'trace-load',
      }), {
        status: 503,
        headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-load' },
      }))
      .mockResolvedValueOnce(jsonResponse(providers))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<ModelSettingsPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent('模型服务暂时不可用')
    expect(screen.getByText('追踪编号：trace-load')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新加载' }))
    expect(await screen.findByRole('article', { name: 'DeepSeek' })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('停用模型不能测试连接或设为默认', async () => {
    const disabled = {
      id: 'disabled',
      displayName: '备用模型',
      model: 'offline-model',
      enabled: false,
      defaultProvider: false,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([providers[0], disabled])))

    render(<ModelSettingsPage />)

    const disabledCard = await screen.findByRole('article', { name: '备用模型' })
    expect(within(disabledCard).getByRole('button', { name: '测试连接' })).toBeDisabled()
    expect(within(disabledCard).queryByRole('button', { name: '设为默认' })).not.toBeInTheDocument()
  })

  it('同一模型测试进行中不会重复请求，失败后显示 traceId', async () => {
    let resolveTest!: (response: Response) => void
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/ai/providers') return Promise.resolve(jsonResponse(providers))
      return new Promise<Response>((resolve) => {
        resolveTest = resolve
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<ModelSettingsPage />)

    const deepSeekCard = await screen.findByRole('article', { name: 'DeepSeek' })
    const testButton = within(deepSeekCard).getByRole('button', { name: '测试连接' })
    await user.dblClick(testButton)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/deepseek/test'))).toHaveLength(1)
    expect(within(deepSeekCard).getByRole('button', { name: '正在测试' })).toBeDisabled()

    resolveTest(new Response(JSON.stringify({
      code: 'RATE_LIMITED',
      message: '测试请求过于频繁',
      traceId: 'trace-test',
    }), {
      status: 429,
      headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-test' },
    }))
    expect(await within(deepSeekCard).findByRole('alert')).toHaveTextContent('测试请求过于频繁')
    expect(within(deepSeekCard).getByText('追踪编号：trace-test')).toBeInTheDocument()
  })

  it('切换失败不改变默认模型，并恢复操作按钮', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/ai/providers' && !init?.method) {
        return Promise.resolve(jsonResponse(providers))
      }
      return Promise.resolve(new Response(JSON.stringify({
        code: 'CONFLICT',
        message: '默认模型切换失败',
        traceId: 'trace-switch',
      }), {
        status: 409,
        headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace-switch' },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()

    render(<ModelSettingsPage />)

    const openAiCard = await screen.findByRole('article', { name: 'OpenAI' })
    const deepSeekCard = screen.getByRole('article', { name: 'DeepSeek' })
    await user.click(within(deepSeekCard).getByRole('button', { name: '设为默认' }))

    expect(await within(deepSeekCard).findByRole('alert')).toHaveTextContent('默认模型切换失败')
    expect(within(openAiCard).getByText('当前默认模型')).toBeInTheDocument()
    expect(within(deepSeekCard).getByRole('button', { name: '设为默认' })).toBeEnabled()
  })
})
