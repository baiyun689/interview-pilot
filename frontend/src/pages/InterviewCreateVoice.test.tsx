import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InterviewCreatePage } from './InterviewCreatePage'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const capabilities = {
  enabled: true,
  supportedMimeTypes: ['audio/webm', 'audio/ogg'],
  maxRecordingSeconds: 300,
  maxUploadBytes: 8388608,
  ttsEnabled: true,
}

const preset = {
  id: 'java-backend', displayName: 'Java 后端开发', description: 'Java 工程面试',
  jobTitle: 'Java 后端开发工程师',
  jobDescription: '负责后端系统设计与开发\n熟悉 Java、JVM 与并发编程\n熟悉 Spring Boot 与事务边界\n熟悉 MySQL、Redis 与消息队列',
  presetVersion: 'sha256-version',
}

/** 组装创建页 fetch mock：capabilities 可覆盖，其余端点与既有创建页测试一致。 */
function createPageFetch(caps: () => Response) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/api/voice/capabilities')) return caps()
    if (url.endsWith('/api/resumes')) return json([])
    if (url.endsWith('/api/ai/providers')) return json([{ id: 'dashscope', displayName: '通义千问', model: 'qwen-plus', enabled: true, defaultProvider: true }])
    if (url.endsWith('/api/interview-presets')) return json([preset])
    if (url.endsWith('/api/knowledge-bases')) return json([])
    if (url.endsWith('/api/interviews') && init?.method === 'POST') {
      return json({ sessionId: 'session-1', status: 'PREPARING', preparationTaskId: 'task-1' }, 202)
    }
    throw new Error(`unexpected request: ${url}`)
  })
}

function renderCreate(fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock)
  return render(<MemoryRouter initialEntries={['/interviews/new']} future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
    <Routes>
      <Route path="/interviews/new" element={<InterviewCreatePage />} />
      <Route path="/interviews/:sessionId" element={<p>面试准备页</p>} />
    </Routes>
  </MemoryRouter>)
}

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('面试创建：语音模式（Task 10）', () => {
  it('语音能力可用时展示语音面试选项，创建请求携带 interviewMode=VOICE', async () => {
    const fetchMock = createPageFetch(() => json(capabilities))
    const user = userEvent.setup()
    renderCreate(fetchMock)

    const voiceRadio = await screen.findByLabelText('语音面试')
    expect(voiceRadio).toBeEnabled()
    expect(screen.getByLabelText('文字面试')).toBeChecked()
    await user.click(voiceRadio)
    expect(voiceRadio).toBeChecked()

    await user.click(screen.getByRole('button', { name: '创建并准备题库' }))
    expect(await screen.findByText('面试准备页')).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    const body = JSON.parse(String(post?.[1]?.body)) as Record<string, unknown>
    expect(body.interviewMode).toBe('VOICE')
    expect(body).not.toHaveProperty('apiKey')
    expect(body).not.toHaveProperty('providerAddress')
  })

  it('语音不可用（enabled=false）时禁用语音选项并展示原因，且不暴露服务端细节', async () => {
    const fetchMock = createPageFetch(() => json({ ...capabilities, enabled: false }))
    renderCreate(fetchMock)

    const voiceRadio = await screen.findByLabelText('语音面试')
    expect(voiceRadio).toBeDisabled()
    expect(screen.getByText('语音面试未启用：未配置语音识别 Provider')).toBeInTheDocument()
    // 原因文案只来自能力开关，不含 Provider/模型/密钥等敏感配置
    expect(screen.queryByText(/sk-|api.?key|cosyvoice|base.?url|dashscope/i)).not.toBeInTheDocument()
  })

  it('能力接口失败时禁用语音选项并给出通用原因', async () => {
    const fetchMock = createPageFetch(() => json({ code: 'VOICE_DISABLED', message: '语音服务未开放' }, 503))
    renderCreate(fetchMock)

    const voiceRadio = await screen.findByLabelText('语音面试')
    expect(voiceRadio).toBeDisabled()
    expect(screen.getByText('语音面试未启用：语音服务暂时不可用')).toBeInTheDocument()
    // 服务端错误消息不进入 DOM（不暴露内部实现）
    expect(screen.queryByText(/语音服务未开放|VOICE_DISABLED/)).not.toBeInTheDocument()
  })

  it('能力未返回时语音选项默认禁用，加载后按能力开关解锁', async () => {
    let resolveCapabilities: (value: Response) => void = () => {}
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/voice/capabilities')) {
        return await new Promise<Response>((resolve) => { resolveCapabilities = resolve })
      }
      if (url.endsWith('/api/resumes') || url.endsWith('/api/knowledge-bases')) return json([])
      if (url.endsWith('/api/interview-presets')) return json([preset])
      return json([{ id: 'dashscope', displayName: '通义千问', model: 'qwen-plus', enabled: true, defaultProvider: true }])
    })
    renderCreate(fetchMock)

    const voiceRadio = screen.getByLabelText('语音面试')
    expect(voiceRadio).toBeDisabled()
    // 能力检测中给出提示（评审 #8）
    expect(screen.getByText('正在检测语音能力…')).toBeInTheDocument()
    resolveCapabilities(json(capabilities))
    await waitFor(() => expect(voiceRadio).toBeEnabled())
    expect(screen.queryByText('正在检测语音能力…')).not.toBeInTheDocument()
  })
})
