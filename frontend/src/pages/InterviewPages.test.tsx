import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { InterviewCreatePage } from './InterviewCreatePage'
import { InterviewLivePage } from './InterviewLivePage'
import { InterviewReportPage } from './InterviewReportPage'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const preset = {
  id: 'java-backend', displayName: 'Java 后端开发', description: 'Java 工程面试',
  jobTitle: 'Java 后端开发工程师',
  jobDescription: '负责后端系统设计与开发\n熟悉 Java、JVM 与并发编程\n熟悉 Spring Boot 与事务边界\n熟悉 MySQL、Redis 与消息队列',
  presetVersion: 'sha256-version',
}

const baseSession = {
  sessionId: 'session-1', resumeId: null, jobTitle: 'Java 后端开发工程师',
  jdText: '负责后端系统设计与开发', difficulty: 'MEDIUM', interviewSize: 'STANDARD',
  jobSourceType: 'PRESET', currentTurnNo: 0, currentMainQuestionNo: 0,
  totalMainQuestionCount: 9,
  providerId: 'dashscope', modelName: 'qwen-plus', preparationTaskId: 'task-1',
  safeError: null, turns: [],
}

function renderRoute(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]} future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
    <Routes>
      <Route path={route} element={element} />
      <Route path="/interviews/:sessionId" element={<p>面试准备页</p>} />
    </Routes>
  </MemoryRouter>)
}

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

describe('固定流程面试创建', () => {
  it('提交服务端预设和固定规模，创建后进入 PREPARING 页面', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/api/resumes')) return json([])
      if (url.endsWith('/api/ai/providers')) return json([{ id: 'dashscope', displayName: '通义千问', model: 'qwen-plus', enabled: true, defaultProvider: true }])
      if (url.endsWith('/api/interview-presets')) return json([preset])
      if (url.endsWith('/api/knowledge-bases')) return json([])
      if (url.endsWith('/api/interviews') && init?.method === 'POST') {
        return json({ sessionId: 'session-1', status: 'PREPARING', preparationTaskId: 'task-1' }, 202)
      }
      throw new Error(`unexpected request: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute('/interviews/new', <InterviewCreatePage />, '/interviews/new')

    expect(await screen.findByRole('option', { name: 'Java 后端开发' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('面试规模'), 'DEEP')
    await user.click(screen.getByRole('button', { name: '创建并准备题库' }))

    expect(await screen.findByText('面试准备页')).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({
      resumeId: null,
      jobSource: { type: 'PRESET', presetId: 'java-backend' },
      difficulty: 'MEDIUM', interviewSize: 'DEEP', providerId: 'dashscope', knowledgeBaseIds: [],
      interviewMode: 'TEXT',
    })
  })

  it('预设岗位以简洁的岗位需求卡片展示，不暴露服务端实现说明', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/resumes') || url.endsWith('/api/knowledge-bases')) return json([])
      if (url.endsWith('/api/interview-presets')) return json([preset])
      return json([{ id: 'dashscope', displayName: '通义千问', model: 'qwen-plus', enabled: true, defaultProvider: true }])
    }))

    renderRoute('/interviews/new', <InterviewCreatePage />, '/interviews/new')

    expect(await screen.findByText('岗位概览')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Java 后端开发工程师' })).toBeInTheDocument()
    expect(screen.getByText('岗位需求')).toBeInTheDocument()
    expect(screen.getByText('熟悉 Java、JVM 与并发编程')).toBeInTheDocument()
    expect(screen.getByText('熟悉 Spring Boot 与事务边界')).toBeInTheDocument()
    expect(screen.getByText('熟悉 MySQL、Redis 与消息队列')).toBeInTheDocument()
    expect(screen.queryByText(/服务端固化|不可修改/)).not.toBeInTheDocument()
  })

  it('自定义 JD 要求岗位名称和完整描述', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/resumes') || url.endsWith('/api/knowledge-bases')) return json([])
      if (url.endsWith('/api/interview-presets')) return json([preset])
      return json([{ id: 'dashscope', displayName: '通义千问', model: 'qwen-plus', enabled: true, defaultProvider: true }])
    }))
    const user = userEvent.setup()
    renderRoute('/interviews/new', <InterviewCreatePage />, '/interviews/new')
    await screen.findByRole('option', { name: 'Java 后端开发' })
    await user.click(screen.getByLabelText('自定义 JD'))
    await user.click(screen.getByRole('button', { name: '创建并准备题库' }))
    expect(screen.getByLabelText('岗位名称')).toHaveAttribute('maxlength', '200')
    expect(screen.getByLabelText('岗位名称')).toBeRequired()
    expect(screen.getByLabelText('完整 JD')).toHaveAttribute('maxlength', '20000')
    expect(screen.getByLabelText('完整 JD')).toBeRequired()
  })
})

describe('固定流程文字面试', () => {
  it('READY 后显式开始，并显示固定自我介绍首轮', async () => {
    const ready = { ...baseSession, status: 'READY' }
    const interviewing = {
      ...baseSession, status: 'INTERVIEWING', currentTurnNo: 1, currentMainQuestionNo: 1,
      turns: [{
        requestId: null, turnNo: 1, status: 'ASKED', difficulty: 'MEDIUM',
        phase: 'SELF_INTRODUCTION', questionType: 'SELF_INTRODUCTION',
        question: '请先做一个简短的自我介绍，重点说明与你应聘的 Java 后端岗位最相关的经历。',
        askedAt: '2026-09-01T08:00:00Z', answer: null, answeredAt: null, processingError: null,
      }],
    }
    let started = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.endsWith('/start') && init?.method === 'POST') { started = true; return json(interviewing) }
      if (url.includes('/api/interviews/session-1')) return json(started ? interviewing : ready)
      throw new Error(`unexpected request: ${url}`)
    }))
    const user = userEvent.setup()
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')

    await user.click(await screen.findByRole('button', { name: '开始面试' }))
    expect(await screen.findByText(/请先做一个简短的自我介绍/)).toBeInTheDocument()
    expect(screen.getByText('主问题进度 1 / 9')).toBeInTheDocument()
    expect(screen.getByLabelText('你的回答')).toBeEnabled()
    expect(screen.queryByText(/即时评分|反馈|能力标签/)).not.toBeInTheDocument()
  })

  it('READY 说明每个主问题包含一至两次不占主问题数量的追问', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ ...baseSession, status: 'READY' })))
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')

    expect(await screen.findByText('题库准备完成')).toBeInTheDocument()
    expect(screen.getByText('共 9 个主流程问题；除自我介绍外，每题包含 1～2 次追问。')).toBeInTheDocument()
  })

  it('PREPARING 只展示可靠异步准备状态', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ ...baseSession, status: 'PREPARING' })))
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    expect(await screen.findByText('正在准备完整题库')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始面试' })).not.toBeInTheDocument()
  })

  it('断线后保留当前轮 requestId，并禁止对 PROCESSING 轮重复提交', async () => {
    const askedTurn = {
      turnNo: 1, status: 'ASKED', phase: 'SELF_INTRODUCTION',
      questionType: 'SELF_INTRODUCTION', question: '请介绍 Java 后端经历',
      askedAt: '2026-09-01T08:00:00Z', answer: null, answeredAt: null,
    }
    const asked = { ...baseSession, status: 'INTERVIEWING', currentTurnNo: 1, turns: [askedTurn] }
    const processing = {
      ...asked,
      turns: [{ ...askedTurn, status: 'PROCESSING', answer: '我的回答内容足够完整' }],
    }
    let getCount = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') throw new TypeError('connection lost')
      getCount++
      return json(getCount === 1 ? asked : processing)
    }))
    const user = userEvent.setup()
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    const answer = await screen.findByLabelText('你的回答')
    await user.type(answer, '我的回答内容足够完整')
    await user.click(screen.getByRole('button', { name: '提交回答' }))

    await waitFor(() => expect(screen.getByLabelText('你的回答')).toBeDisabled())
    await waitFor(() => expect(
      sessionStorage.getItem('interview-answer-request:session-1')).not.toBeNull())
    const pending = JSON.parse(sessionStorage.getItem('interview-answer-request:session-1') ?? 'null')
    expect(pending).toMatchObject({ turnNo: 1 })
    expect(pending.requestId).toMatch(/[0-9a-f-]{36}/)
  })
})

describe('最终报告', () => {
  it('只在结束后展示总分、阶段分和可追溯技术参考', async () => {
    const completed = { ...baseSession, status: 'COMPLETED', currentTurnNo: 9 }
    const report = {
      sessionId: 'session-1', reportId: 'report-1', createdAt: '2026-09-01T09:00:00Z',
      report: {
        overallScore: 82,
        phaseScores: { SELF_INTRODUCTION: 75, FUNDAMENTALS: 85, PROJECT_EXPERIENCE: 82, SCENARIO_TRADEOFF: 86 },
        strengths: ['能说明失败窗口'], improvements: ['补充容量指标'],
        technicalReferences: [{ sourceId: 'point-1', note: '事务消息参考' }],
        conflictNotes: [], summary: '具备扎实的 Java 后端基础。',
        ragAvailability: { FUNDAMENTALS: 'RETRIEVED' },
      },
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      return url.endsWith('/report') ? json(report) : json(completed)
    }))
    renderRoute('/interviews/session-1/report', <InterviewReportPage pollIntervalMs={5} />, '/interviews/:sessionId/report')

    expect(await screen.findByText('82')).toBeInTheDocument()
    expect(screen.getByText('具备扎实的 Java 后端基础。')).toBeInTheDocument()
    expect(screen.getByText(/事务消息参考/)).toBeInTheDocument()
    expect(screen.getByText('基础原理：85 分')).toBeInTheDocument()
  })
})
