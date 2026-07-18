import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ResumeDetailPage } from './ResumeDetailPage'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'header-trace' },
  })
}

const base = {
  id: 5,
  originalFilename: 'candidate.pdf',
  status: 'PENDING',
  duplicate: false,
  analysisTaskId: '9d84fe79-476d-47dc-a4ab-5c6871fa65f4',
  createdAt: '2026-07-14T01:00:00Z',
  profile: null,
  analysisError: null,
}

function renderDetail() {
  return render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={['/resumes/5']}>
      <Routes><Route path="/resumes/:resumeId" element={<ResumeDetailPage />} /></Routes>
    </MemoryRouter>,
  )
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('简历分析详情', () => {
  it('READY 时渲染完整画像且不显示重试', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      ...base,
      status: 'READY',
      profile: {
        summary: '五年 Java 后端经验',
        technicalSkills: ['Spring Boot', 'Redis'],
        projects: [{ name: '订单平台', description: '负责高并发链路', technologies: ['MySQL'] }],
        strengths: ['工程基础扎实'],
        risks: [],
      },
    })))
    renderDetail()

    expect(await screen.findByText('五年 Java 后端经验')).toBeInTheDocument()
    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
    expect(screen.getByText('订单平台')).toBeInTheDocument()
    expect(screen.getByText('工程基础扎实')).toBeInTheDocument()
    expect(screen.getByText('暂无风险提示')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
  })

  it('轮询不重叠，终态后停止，卸载后不再请求', async () => {
    vi.useFakeTimers()
    let resolveSecond!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(base))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveSecond = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const view = renderDetail()
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)

    await act(async () => { resolveSecond(json({
      ...base,
      status: 'READY',
      profile: { summary: '完成', technicalSkills: [], projects: [], strengths: [], risks: [] },
    })); await Promise.resolve() })
    await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    view.unmount()
    await act(async () => { await vi.runOnlyPendingTimersAsync() })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('路由切换后忽略旧简历的迟到响应', async () => {
    let resolveOld!: (response: Response) => void
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      if (String(input).endsWith('/5')) {
        return new Promise<Response>((resolve) => { resolveOld = resolve })
      }
      return Promise.resolve(json({
        ...base,
        id: 6,
        originalFilename: 'new-route.txt',
        status: 'READY',
        profile: { summary: '新路由画像', technicalSkills: [], projects: [], strengths: [], risks: [] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(
      <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={['/resumes/5']}>
        <Link to="/resumes/6">切换简历</Link>
        <Routes><Route path="/resumes/:resumeId" element={<ResumeDetailPage />} /></Routes>
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('link', { name: '切换简历' }))
    expect(await screen.findByText('新路由画像')).toBeInTheDocument()
    await act(async () => {
      resolveOld(json({ ...base, originalFilename: 'old-route.txt', status: 'FAILED' }))
      await Promise.resolve()
    })
    expect(screen.queryByText('old-route.txt')).not.toBeInTheDocument()
    expect(screen.getByText('new-route.txt')).toBeInTheDocument()
  })

  it('仅 FAILED 显示重试，成功后恢复轮询', async () => {
    const failed = { ...base, status: 'FAILED', analysisError: '模型暂时不可用' }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(failed))
      .mockResolvedValueOnce(json({ taskId: base.analysisTaskId, status: 'PENDING' }, 202))
      .mockResolvedValueOnce(json({ ...base, status: 'PENDING' }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderDetail()

    expect(await screen.findByText('模型暂时不可用')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新分析' }))

    expect(await screen.findByText('重试请求已提交')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/tasks/${base.analysisTaskId}/retry`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(await screen.findAllByText('等待分析')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
  })

  it('重试失败恢复按钮并显示 trace-aware 错误', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ ...base, status: 'FAILED', analysisError: '失败' }))
      .mockResolvedValueOnce(json({ message: '暂时无法重试', traceId: 'trace-retry' }, 409)))
    const user = userEvent.setup()
    renderDetail()

    await user.click(await screen.findByRole('button', { name: '重新分析' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法重试')
    expect(screen.getByText('追踪编号：header-trace')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新分析' })).toBeEnabled()
  })

  it('路由切换后忽略旧简历重试的迟到成功响应', async () => {
    let resolveRetry!: (response: Response) => void
    let postCount = 0
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        postCount += 1
        if (postCount === 1) return new Promise<Response>((resolve) => { resolveRetry = resolve })
        return new Promise<Response>(() => undefined)
      }
      if (url.endsWith('/5')) return Promise.resolve(json({ ...base, status: 'FAILED', analysisError: '旧简历失败' }))
      return Promise.resolve(json({
        ...base,
        id: 6,
        originalFilename: 'current-route.txt',
        analysisTaskId: 'a8dc8a3d-46c2-4850-b18f-eaab39a72720',
        status: 'FAILED',
        analysisError: '当前简历失败',
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(
      <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={['/resumes/5']}>
        <Link to="/resumes/6">切换到新简历</Link>
        <Routes><Route path="/resumes/:resumeId" element={<ResumeDetailPage />} /></Routes>
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: '重新分析' }))
    await user.click(screen.getByRole('link', { name: '切换到新简历' }))
    expect(await screen.findByText('当前简历失败')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '重新分析' }))
    expect(screen.getByRole('button', { name: '正在重试' })).toBeDisabled()

    await act(async () => {
      resolveRetry(json({ taskId: base.analysisTaskId, status: 'PENDING' }, 202))
      await Promise.resolve()
    })

    expect(screen.getByText('current-route.txt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '正在重试' })).toBeDisabled()
    expect(screen.queryByText('重试请求已提交')).not.toBeInTheDocument()
    expect(screen.queryByText('等待分析')).not.toBeInTheDocument()
  })

  it('路由切换后忽略旧简历重试的迟到失败响应', async () => {
    let rejectRetry!: (reason: unknown) => void
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'POST') {
        return new Promise<Response>((_resolve, reject) => { rejectRetry = reject })
      }
      if (url.endsWith('/5')) return Promise.resolve(json({ ...base, status: 'FAILED', analysisError: '旧简历失败' }))
      return Promise.resolve(json({
        ...base,
        id: 6,
        originalFilename: 'current-route.txt',
        status: 'READY',
        profile: { summary: '当前路由画像', technicalSkills: [], projects: [], strengths: [], risks: [] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(
      <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={['/resumes/5']}>
        <Link to="/resumes/6">切换到新简历</Link>
        <Routes><Route path="/resumes/:resumeId" element={<ResumeDetailPage />} /></Routes>
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: '重新分析' }))
    await user.click(screen.getByRole('link', { name: '切换到新简历' }))
    expect(await screen.findByText('当前路由画像')).toBeInTheDocument()

    await act(async () => {
      rejectRetry(new Error('old route network failure'))
      await Promise.resolve()
    })

    expect(screen.getByText('current-route.txt')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
