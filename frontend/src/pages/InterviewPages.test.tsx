import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { InterviewCreatePage } from './InterviewCreatePage'
import { InterviewHistoryPage } from './InterviewHistoryPage'
import { InterviewLivePage } from './InterviewLivePage'
import { InterviewReportPage } from './InterviewReportPage'

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

const session = {
  sessionId: 'session-1', resumeId: 7, jobTitle: 'Java AI 后端', jdText: 'Spring AI',
  status: 'INTERVIEWING', difficulty: 'MEDIUM', currentTurnNo: 1, totalTurnBudget: 8,
  providerId: 'deepseek', modelName: 'deepseek-chat',
  plan: { competencies: ['Java', 'AI'], totalTurnBudget: 8 },
  turns: [{ requestId: null, turnNo: 1, status: 'ASKED', difficulty: 'MEDIUM', question: '解释乐观锁',
    targetCompetency: 'Java', askedAt: '2026-07-14T01:00:00Z', answer: null, feedback: null, score: null,
    evidence: [], missingPoints: [], decision: null, nextDifficulty: null, answeredAt: null, processingError: null }],
}

const interviewSkills = [
  { id: 'java-backend', displayName: 'Java 后端', description: 'Java 工程面试', group: 'JOB', icon: 'code', defaultCompetencies: ['Java'], version: 'v1' },
  { id: 'custom', displayName: '自定义岗位', description: '自定义', group: 'CUSTOM', icon: 'briefcase', defaultCompetencies: ['工程实践'], version: 'v1' },
]

function renderRoute(path: string, element: React.ReactNode, route: string) {
  return render(<MemoryRouter initialEntries={[path]} future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
    <Routes><Route path={route} element={element} /><Route path="/interviews/:sessionId" element={<p>live destination</p>} /></Routes>
  </MemoryRouter>)
}

function renderSwitchingRoute(path: string, element: React.ReactNode, route: string, destination: string) {
  return render(<MemoryRouter initialEntries={[path]} future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
    <Link to={destination}>切换会话</Link>
    <Routes><Route path={route} element={element} /></Routes>
  </MemoryRouter>)
}

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('创建面试', () => {
  it('只显示 READY 简历和 enabled provider，校验输入并阻止双提交', async () => {
    let resolveCreate!: (value: Response) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([{ id: 1, originalFilename: 'wait.txt', status: 'PENDING' }, { id: 7, originalFilename: 'ready.txt', status: 'READY' }]))
      .mockResolvedValueOnce(json([{ id: 'off', displayName: 'Off', model: 'x', enabled: false }, { id: 'deepseek', displayName: 'DeepSeek', model: 'deepseek-chat', enabled: true }]))
      .mockResolvedValueOnce(json(interviewSkills))
      .mockResolvedValueOnce(json([]))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveCreate = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute('/interviews/new', <InterviewCreatePage />, '/interviews/new')

    expect(await screen.findByRole('option', { name: /ready.txt/ })).toBeInTheDocument()
    expect(screen.queryByText('wait.txt')).not.toBeInTheDocument()
    expect(screen.getByRole('option', { name: /DeepSeek.*deepseek-chat/ })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText(/^候选人简历/), '7')
    await user.selectOptions(screen.getByLabelText('面试方向'), 'custom')
    await user.type(screen.getByLabelText('岗位名称'), '  Java AI 后端  ')
    await user.type(screen.getByLabelText('岗位描述'), '  Spring AI 与并发控制  ')
    await user.selectOptions(screen.getByLabelText('模型'), 'deepseek')
    await user.dblClick(screen.getByRole('button', { name: '创建并开始面试' }))

    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    const body = JSON.parse(String(fetchMock.mock.calls.at(-1)?.[1]?.body))
    expect(body).toMatchObject({ resumeId: 7, skillId: 'custom', jobTitle: 'Java AI 后端', jdText: 'Spring AI 与并发控制', providerId: 'deepseek' })
    resolveCreate(json(session, 201))
    expect(await screen.findByText('live destination')).toBeInTheDocument()
  })

  it('独立展示资源错误和 trace id，并在空资源时给出入口', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ message: '简历服务失败', traceId: 'trace-resume' }, 503))
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json(interviewSkills)))
    renderRoute('/interviews/new', <InterviewCreatePage />, '/interviews/new')
    expect(await screen.findByText('简历服务失败')).toBeInTheDocument()
    expect(screen.getByText('追踪编号：trace-resume')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '前往模型设置' })).toHaveAttribute('href', '/settings')
  })

  it('创建失败恢复按钮并显示 trace id', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json([{ id: 7, originalFilename: 'ready.txt', status: 'READY' }]))
      .mockResolvedValueOnce(json([{ id: 'deepseek', displayName: 'DeepSeek', model: 'chat', enabled: true }]))
      .mockResolvedValueOnce(json(interviewSkills))
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json({ message: '创建失败', traceId: 'trace-create' }, 503)))
    const user = userEvent.setup()
    renderRoute('/interviews/new', <InterviewCreatePage />, '/interviews/new')
    await screen.findByRole('option', { name: /ready.txt/ })
    await user.selectOptions(screen.getByLabelText(/^候选人简历/), '7')
    await user.selectOptions(screen.getByLabelText('面试方向'), 'custom')
    await user.type(screen.getByLabelText('岗位名称'), 'Java')
    await user.type(screen.getByLabelText('岗位描述'), 'Backend')
    await user.selectOptions(screen.getByLabelText('模型'), 'deepseek')
    await user.click(screen.getByRole('button', { name: '创建并开始面试' }))
    expect(await screen.findByText('追踪编号：trace-create')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '创建并开始面试' })).toBeEnabled()
  })
})

describe('实时面试', () => {
  beforeEach(() => vi.stubGlobal('crypto', { randomUUID: vi.fn().mockReturnValueOnce('request-1').mockReturnValueOnce('request-2') }))

  it('渐进展示反馈以及正交的流程动作和难度调整，终态后刷新持久化会话', async () => {
    const events = [
      'event: ACCEPTED\ndata: {"type":"ACCEPTED","sessionId":"session-1","turnNo":1,"payload":{"requestId":"request-1","replayed":false}}\n\n',
      'event: FEEDBACK\ndata: {"type":"FEEDBACK","sessionId":"session-1","turnNo":1,"payload":{"score":82,"feedback":"证据充分","evidence":["版本字段"],"missingPoints":["重试"]}}\n\n',
      'event: DECISION\ndata: {"type":"DECISION","sessionId":"session-1","turnNo":1,"payload":{"decision":{"nextStep":"FOLLOW_UP","difficultyAdjustment":"INCREASE","targetCompetency":"Java","probeFocus":"并发","reason":"继续深入","confidence":0.9}}}\n\n',
      'event: COMPLETED\ndata: {"type":"COMPLETED","sessionId":"session-1","turnNo":1,"payload":{"status":"INTERVIEWING"}}\n\n',
    ].join('')
    const fetchMock = vi.fn().mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(new Response(events, { headers: { 'Content-Type': 'text/event-stream' } }))
      .mockResolvedValueOnce(json({ ...session, currentTurnNo: 2 }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    expect(await screen.findByText('解释乐观锁')).toBeInTheDocument()
    expect(screen.getByText('DeepSeek · deepseek-chat')).toBeInTheDocument()
    await user.type(screen.getByLabelText('你的回答'), '使用 version 字段')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    expect(await screen.findByText('证据充分')).toBeInTheDocument()
    expect(screen.getByText('流程动作：追问')).toBeInTheDocument()
    expect(screen.getByText('难度调整：提高')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(JSON.parse(String(fetchMock.mock.calls[1][1]?.body)).requestId).toBe('request-1')
  })

  it('流断开后先恢复会话，只允许显式新 requestId 重试', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json(session))
      .mockRejectedValueOnce(new TypeError('disconnect'))
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(new Response('event: ERROR\ndata: {"type":"ERROR","sessionId":"session-1","turnNo":1,"payload":{"code":"AI_TIMEOUT","message":"模型超时","retryable":true}}\n\n', { headers: { 'Content-Type': 'text/event-stream' } }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    await screen.findByText('解释乐观锁')
    await user.type(screen.getByLabelText('你的回答'), 'answer')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    expect(await screen.findByText(/连接中断/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新提交' }))
    expect(JSON.parse(String(fetchMock.mock.calls[3][1]?.body)).requestId).toBe('request-2')
  })

  it('卸载时中止所属 SSE，迟到回调不能继续刷新会话', async () => {
    let streamSignal: AbortSignal | undefined
    let rejectStream!: (reason: unknown) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockImplementationOnce((_url, init: RequestInit) => {
        streamSignal = init.signal ?? undefined
        return new Promise<Response>((_resolve, reject) => { rejectStream = reject })
      })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const view = renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    await screen.findByText('解释乐观锁')
    await user.type(screen.getByLabelText('你的回答'), 'answer')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    view.unmount()
    expect(streamSignal?.aborted).toBe(true)
    await act(async () => { rejectStream(new DOMException('aborted', 'AbortError')); await Promise.resolve() })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['request COMPLETED', 'COMPLETED', 1, 'INTERVIEWING'],
    ['request FAILED', 'FAILED', 1, 'INTERVIEWING'],
    ['session advanced', 'ASKED', 2, 'INTERVIEWING'],
    ['session terminal', 'ASKED', 1, 'COMPLETED'],
  ])('clean EOF 恢复到持久化 %s 后不提供断连重试', async (_case, turnStatus, currentTurnNo, sessionStatus) => {
    const recovered = {
      ...session,
      currentTurnNo,
      status: sessionStatus,
      turns: [{ ...session.turns[0], requestId: turnStatus === 'ASKED' ? null : 'request-1', status: turnStatus }],
    }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(new Response('', { headers: { 'Content-Type': 'text/event-stream' } }))
      .mockResolvedValueOnce(json(recovered))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    await screen.findByText('解释乐观锁')
    await user.type(screen.getByLabelText('你的回答'), 'answer')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(screen.queryByText(/连接中断/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新提交' })).not.toBeInTheDocument()
  })

  it('ACCEPTED 与 NEXT_QUESTION 都产生 aria-live 渐进更新', async () => {
    const events = [
      'event: ACCEPTED\ndata: {"type":"ACCEPTED","sessionId":"session-1","turnNo":1,"payload":{"requestId":"request-1","replayed":true}}\n\n',
      'event: NEXT_QUESTION\ndata: {"type":"NEXT_QUESTION","sessionId":"session-1","turnNo":1,"payload":{"question":"解释 Redis 限流","targetCompetency":"缓存","difficulty":"HARD"}}\n\n',
      'event: COMPLETED\ndata: {"type":"COMPLETED","sessionId":"session-1","turnNo":1,"payload":{"status":"INTERVIEWING"}}\n\n',
    ].join('')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(new Response(events, { headers: { 'Content-Type': 'text/event-stream' } }))
      .mockResolvedValueOnce(json({ ...session, currentTurnNo: 2 })))
    const user = userEvent.setup()
    renderRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId')
    await screen.findByText('解释乐观锁')
    await user.type(screen.getByLabelText('你的回答'), 'answer')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    const live = await screen.findByRole('status', { name: '本轮处理进度' })
    expect(live).toHaveTextContent('已恢复已接收的提交')
    expect(live).toHaveTextContent('下一题：解释 Redis 限流')
    expect(live).toHaveTextContent('缓存 · 困难')
  })

  it('空闲切换 live 路由会清空旧回答和渐进错误状态', async () => {
    const second = { ...session, sessionId: 'session-2', jobTitle: '第二场面试' }
    const oldEvents = [
      'event: ACCEPTED\ndata: {"type":"ACCEPTED","sessionId":"session-1","turnNo":1,"payload":{"requestId":"request-1","replayed":false}}\n\n',
      'event: ERROR\ndata: {"type":"ERROR","sessionId":"session-1","turnNo":1,"payload":{"code":"AI_TIMEOUT","message":"旧错误","retryable":true}}\n\n',
    ].join('')
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(new Response(oldEvents, { headers: { 'Content-Type': 'text/event-stream' } }))
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(json(second)))
    const user = userEvent.setup()
    renderSwitchingRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId', '/interviews/session-2')
    await screen.findByText('解释乐观锁')
    await user.type(screen.getByLabelText('你的回答'), '旧回答')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    expect(await screen.findByText('旧错误')).toBeInTheDocument()
    expect(screen.getByText('回答已接收，正在生成反馈')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: '切换会话' }))
    expect(await screen.findByText('第二场面试')).toBeInTheDocument()
    expect(screen.getByLabelText('你的回答')).toHaveValue('')
    expect(screen.getByRole('button', { name: '提交回答' })).toBeDisabled()
    expect(screen.queryByText('旧错误')).not.toBeInTheDocument()
    expect(screen.queryByText('回答已接收，正在生成反馈')).not.toBeInTheDocument()
  })

  it('请求中切换 live 路由会恢复新会话表单且旧 finally 不污染', async () => {
    let rejectOld!: (reason: unknown) => void
    const second = { ...session, sessionId: 'session-2', jobTitle: '第二场面试' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockImplementationOnce(() => new Promise<Response>((_resolve, reject) => { rejectOld = reject }))
      .mockResolvedValueOnce(json(second))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderSwitchingRoute('/interviews/session-1', <InterviewLivePage />, '/interviews/:sessionId', '/interviews/session-2')
    await screen.findByText('解释乐观锁')
    await user.type(screen.getByLabelText('你的回答'), '旧回答')
    await user.click(screen.getByRole('button', { name: '提交回答' }))
    await user.click(screen.getByRole('link', { name: '切换会话' }))
    expect(await screen.findByText('第二场面试')).toBeInTheDocument()
    expect(screen.getByLabelText('你的回答')).toBeEnabled()
    expect(screen.getByLabelText('你的回答')).toHaveValue('')
    await act(async () => { rejectOld(new DOMException('aborted', 'AbortError')); await Promise.resolve() })
    expect(screen.getByLabelText('你的回答')).toBeEnabled()
    expect(screen.queryByText(/连接中断/)).not.toBeInTheDocument()
  })
})

describe('面试历史', () => {
  it('保留后端顺序并显示持久化模型快照和入口', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([
      { sessionId: 's2', jobTitle: 'Second', status: 'EVALUATING', difficulty: 'HARD', currentTurnNo: 8, totalTurnBudget: 8, providerId: 'p2', modelName: 'm2', createdAt: '2026-07-14T02:00:00Z', completedAt: null },
      { sessionId: 's1', jobTitle: 'First', status: 'INTERVIEWING', difficulty: 'MEDIUM', currentTurnNo: 2, totalTurnBudget: 8, providerId: 'p1', modelName: 'm1', createdAt: '2026-07-14T01:00:00Z', completedAt: null },
    ])))
    renderRoute('/interviews', <InterviewHistoryPage />, '/interviews')
    const headings = await screen.findAllByRole('heading', { level: 2 })
    expect(headings.map((node) => node.textContent)).toEqual(['Second', 'First'])
    expect(screen.getByText('p2 · m2')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '查看报告' })).toHaveAttribute('href', '/interviews/s2/report')
    expect(screen.getAllByText('未完成')).toHaveLength(2)
  })

  it('完成的历史记录展示完成时间，未完成记录展示明确语义', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([
      { sessionId: 'done', jobTitle: 'Done', status: 'COMPLETED', difficulty: 'HARD', currentTurnNo: 8, totalTurnBudget: 8, providerId: 'p', modelName: 'm', createdAt: '2026-07-14T01:00:00Z', completedAt: '2026-07-14T03:00:00Z' },
      { sessionId: 'open', jobTitle: 'Open', status: 'INTERVIEWING', difficulty: 'MEDIUM', currentTurnNo: 2, totalTurnBudget: 8, providerId: 'p', modelName: 'm', createdAt: '2026-07-14T02:00:00Z', completedAt: null },
    ])))
    renderRoute('/interviews', <InterviewHistoryPage />, '/interviews')
    expect(await screen.findAllByText(/完成时间/)).toHaveLength(2)
    expect(screen.getByText(/2026.*7.*14.*11:00:00/)).toBeInTheDocument()
    expect(screen.getByText('未完成')).toBeInTheDocument()
  })

  it('展示空状态和 trace-aware 错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json([])))
    const empty = renderRoute('/interviews', <InterviewHistoryPage />, '/interviews')
    expect(await screen.findByText('还没有面试')).toBeInTheDocument()
    empty.unmount()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ message: '历史加载失败', traceId: 'trace-history' }, 503)))
    renderRoute('/interviews', <InterviewHistoryPage />, '/interviews')
    expect(await screen.findByText('历史加载失败')).toBeInTheDocument()
    expect(screen.getByText('追踪编号：trace-history')).toBeInTheDocument()
  })
})

describe('面试报告', () => {
  it('轮询 202 后渲染报告、文本评分和会话模型快照', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(json({ sessionId: 'session-1', sessionStatus: 'EVALUATING', taskId: 'task-1', taskStatus: 'PUBLISHED', error: null, retryable: false }, 202))
      .mockResolvedValueOnce(json({ sessionId: 'session-1', reportId: 'report-1', createdAt: '2026-07-14T03:00:00Z', report: { overallScore: 86, competencyScores: { Java: 90, AI: 82 }, strengths: ['并发基础扎实'], improvements: ['补充限流策略'], summary: '适合后端岗位' } }))
    vi.stubGlobal('fetch', fetchMock)
    renderRoute('/interviews/session-1/report', <InterviewReportPage pollIntervalMs={100} />, '/interviews/:sessionId/report')
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(screen.getByText('报告生成中')).toBeInTheDocument()
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })
    expect(screen.getByText('86')).toBeInTheDocument()
    expect(screen.getByText('Java：90 分')).toBeInTheDocument()
    expect(screen.getByText('DeepSeek · deepseek-chat')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('只有 retryable 且带 public taskId 时显示重试并在成功后恢复轮询', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockResolvedValueOnce(json({ sessionId: 'session-1', sessionStatus: 'EVALUATING', taskId: 'task-public', taskStatus: 'DEAD', error: 'internal detail', retryable: true }, 202))
      .mockResolvedValueOnce(json({ taskId: 'task-public', status: 'PENDING' }, 202))
      .mockResolvedValueOnce(json({ sessionId: 'session-1', sessionStatus: 'EVALUATING', taskId: 'task-public', taskStatus: 'PUBLISHED', error: null, retryable: false }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderRoute('/interviews/session-1/report', <InterviewReportPage pollIntervalMs={60_000} />, '/interviews/:sessionId/report')
    const retry = await screen.findByRole('button', { name: '重试生成报告' })
    expect(screen.queryByText('internal detail')).not.toBeInTheDocument()
    await user.click(retry)
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === '/api/tasks/task-public/retry')).toBe(true))
    expect(await screen.findByText('报告生成中')).toBeInTheDocument()
  })

  it('轮询请求不重叠，卸载会中止当前报告请求', async () => {
    vi.useFakeTimers()
    let reportSignal: AbortSignal | undefined
    let resolveReport!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(session))
      .mockImplementationOnce((_url, init: RequestInit) => {
        reportSignal = init.signal ?? undefined
        return new Promise<Response>((resolve) => { resolveReport = resolve })
      })
    vi.stubGlobal('fetch', fetchMock)
    const view = renderRoute('/interviews/session-1/report', <InterviewReportPage pollIntervalMs={100} />, '/interviews/:sessionId/report')
    await act(async () => { await Promise.resolve(); await vi.advanceTimersByTimeAsync(1000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(reportSignal?.aborted).toBe(false)
    view.unmount()
    expect(reportSignal?.aborted).toBe(true)
    await act(async () => { resolveReport(json({ sessionId: 'session-1', sessionStatus: 'EVALUATING', taskId: 'task-1', taskStatus: 'PUBLISHED', error: null, retryable: false }, 202)); await Promise.resolve() })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('切换报告路由立即清除旧结果并阻止迟到结果串场', async () => {
    const oldSession = { ...session, sessionId: 'old', jobTitle: '旧面试', providerId: 'old-provider', modelName: 'old-model' }
    const nextSession = { ...session, sessionId: 'next', jobTitle: '新面试', providerId: 'next-provider', modelName: 'next-model' }
    const oldResult = { sessionId: 'old', reportId: 'old-report', createdAt: '2026-07-14T03:00:00Z', report: { overallScore: 99, competencyScores: { Java: 99 }, strengths: ['旧优势'], improvements: ['旧改进'], summary: '旧报告摘要' } }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(oldSession))
      .mockResolvedValueOnce(json(oldResult))
      .mockResolvedValueOnce(json(nextSession))
      .mockResolvedValueOnce(json({ sessionId: 'next', sessionStatus: 'EVALUATING', taskId: 'next-task', taskStatus: 'PUBLISHED', error: null, retryable: false }, 202))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderSwitchingRoute('/interviews/old/report', <InterviewReportPage pollIntervalMs={60_000} />, '/interviews/:sessionId/report', '/interviews/next/report')
    await screen.findByText('old-provider · old-model')
    expect(await screen.findByText('旧报告摘要')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: '切换会话' }))
    expect(await screen.findByText('next-provider · next-model')).toBeInTheDocument()
    expect(screen.getByText('报告生成中')).toBeInTheDocument()
    expect(screen.queryByText('99')).not.toBeInTheDocument()
    expect(screen.queryByText('旧报告摘要')).not.toBeInTheDocument()
  })
})
