import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ResumeListPage } from './ResumeListPage'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const baseResume = {
  id: 1,
  originalFilename: 'java-backend.txt',
  status: 'PENDING',
  duplicate: false,
  analysisTaskId: '9d84fe79-476d-47dc-a4ab-5c6871fa65f4',
  createdAt: '2026-07-14T01:00:00Z',
  profile: null,
  analysisError: null,
}

afterEach(() => vi.unstubAllGlobals())

function renderPage() {
  return render(<MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}><ResumeListPage /></MemoryRouter>)
}

describe('简历列表与上传', () => {
  it.each([
    [new File([], 'empty.txt', { type: 'text/plain' }), '文件不能为空'],
    [new File(['hello'], 'resume.doc', { type: 'application/msword' }), '仅支持 PDF、DOCX、TXT'],
    [new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'large.pdf'), '文件不能超过 10 MiB'],
  ])('在请求前拒绝非法文件 %#', async (file, expected) => {
    const fetchMock = vi.fn().mockResolvedValue(json([]))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await screen.findByText('还没有简历')

    fireEvent.change(screen.getByLabelText('选择简历文件'), { target: { files: [file] } })

    expect(screen.getByRole('alert')).toHaveTextContent(expected)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('展示四种状态并保留服务端顺序', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json([
      { ...baseResume, id: 1, originalFilename: 'pending.txt', status: 'PENDING' },
      { ...baseResume, id: 2, originalFilename: 'analyzing.txt', status: 'ANALYZING' },
      { ...baseResume, id: 3, originalFilename: 'ready.txt', status: 'READY' },
      { ...baseResume, id: 4, originalFilename: 'failed.txt', status: 'FAILED' },
    ])))
    renderPage()

    expect(await screen.findByText('等待分析')).toBeInTheDocument()
    expect(screen.getByText('分析中')).toBeInTheDocument()
    expect(screen.getByText('已完成')).toBeInTheDocument()
    expect(screen.getByText('分析失败')).toBeInTheDocument()
    expect(screen.getAllByRole('article').map((card) => within(card).getByRole('heading').textContent))
      .toEqual(['pending.txt', 'analyzing.txt', 'ready.txt', 'failed.txt'])
  })

  it.each([
    [202, false, '简历已上传，AI 分析已开始'],
    [200, true, '相同简历已存在，已复用原有分析'],
  ])('根据 HTTP %s 明确反馈上传结果', async (status, duplicate, message) => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json({
        resumeId: 7,
        analysisTaskId: baseResume.analysisTaskId,
        duplicate,
      }, status))
      .mockResolvedValueOnce(json([{ ...baseResume, id: 7, duplicate }]))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('还没有简历')

    const input = screen.getByLabelText('选择简历文件')
    await user.upload(input, new File(['Senior Java developer'], 'resume.txt', { type: 'text/plain' }))
    await user.click(screen.getByRole('button', { name: '上传并分析' }))

    expect(await screen.findByRole('status')).toHaveTextContent(message)
    expect(screen.getByRole('link', { name: '查看分析详情' })).toHaveAttribute('href', '/resumes/7')
    const uploadCalls = fetchMock.mock.calls.filter(([url]) => String(url) === '/api/resumes')
    expect(uploadCalls.some(([, init]) => init?.method === 'POST')).toBe(true)
  })

  it('上传失败恢复按钮并显示追踪编号', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockResolvedValueOnce(json({ message: '服务繁忙', traceId: 'trace-upload' }, 503)))
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('还没有简历')
    await user.upload(screen.getByLabelText('选择简历文件'), new File(['java'], 'resume.txt'))
    await user.click(screen.getByRole('button', { name: '上传并分析' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('服务繁忙')
    expect(screen.getByText('追踪编号：trace-upload')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '上传并分析' })).toBeEnabled()
  })

  it('上传进行中阻止重复提交', async () => {
    let resolveUpload!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json([]))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveUpload = resolve }))
      .mockResolvedValueOnce(json([]))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('还没有简历')
    await user.upload(screen.getByLabelText('选择简历文件'), new File(['java'], 'resume.txt'))

    await user.dblClick(screen.getByRole('button', { name: '上传并分析' }))

    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    resolveUpload(json({ resumeId: 8, analysisTaskId: baseResume.analysisTaskId, duplicate: false }, 202))
    expect(await screen.findByText(/简历已上传/)).toBeInTheDocument()
  })

  it('初始列表请求迟到时不回滚上传后的新列表', async () => {
    let resolveInitial!: (response: Response) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((resolve) => { resolveInitial = resolve }))
      .mockResolvedValueOnce(json({ resumeId: 7, analysisTaskId: baseResume.analysisTaskId, duplicate: false }, 202))
      .mockResolvedValueOnce(json([{ ...baseResume, id: 7, originalFilename: 'new-resume.txt' }]))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()

    await user.upload(screen.getByLabelText('选择简历文件'), new File(['java'], 'new-resume.txt'))
    await user.click(screen.getByRole('button', { name: '上传并分析' }))
    expect(await screen.findByText('new-resume.txt')).toBeInTheDocument()

    await act(async () => {
      resolveInitial(json([]))
      await Promise.resolve()
    })

    expect(screen.getByText('new-resume.txt')).toBeInTheDocument()
    expect(screen.queryByText('还没有简历')).not.toBeInTheDocument()
  })

  it('初始列表请求迟到失败时不覆盖上传刷新的成功状态', async () => {
    let rejectInitial!: (reason: unknown) => void
    const fetchMock = vi.fn()
      .mockImplementationOnce(() => new Promise<Response>((_resolve, reject) => { rejectInitial = reject }))
      .mockResolvedValueOnce(json({ resumeId: 7, analysisTaskId: baseResume.analysisTaskId, duplicate: false }, 202))
      .mockResolvedValueOnce(json([{ ...baseResume, id: 7, originalFilename: 'new-resume.txt' }]))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    renderPage()

    await user.upload(screen.getByLabelText('选择简历文件'), new File(['java'], 'new-resume.txt'))
    await user.click(screen.getByRole('button', { name: '上传并分析' }))
    expect(await screen.findByText('new-resume.txt')).toBeInTheDocument()

    await act(async () => {
      rejectInitial(new Error('stale list failure'))
      await Promise.resolve()
    })

    expect(screen.getByText('new-resume.txt')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('上传在卸载后迟到成功时中止请求且不再启动列表加载', async () => {
    let resolveUpload!: (response: Response) => void
    let uploadSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        uploadSignal = init.signal ?? undefined
        return new Promise<Response>((resolve) => { resolveUpload = resolve })
      }
      return Promise.resolve(json([]))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const view = renderPage()
    await screen.findByText('还没有简历')
    await user.upload(screen.getByLabelText('选择简历文件'), new File(['java'], 'resume.txt'))
    await user.click(screen.getByRole('button', { name: '上传并分析' }))

    expect(uploadSignal).toBeInstanceOf(AbortSignal)
    expect(uploadSignal?.aborted).toBe(false)
    view.unmount()
    expect(uploadSignal?.aborted).toBe(true)

    await act(async () => {
      resolveUpload(json({ resumeId: 9, analysisTaskId: baseResume.analysisTaskId, duplicate: false }, 202))
      await Promise.resolve()
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('上传在卸载后迟到失败时中止请求且不执行后续请求', async () => {
    let rejectUpload!: (reason: unknown) => void
    let uploadSignal: AbortSignal | undefined
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'POST') {
        uploadSignal = init.signal ?? undefined
        return new Promise<Response>((_resolve, reject) => { rejectUpload = reject })
      }
      return Promise.resolve(json([]))
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    const view = renderPage()
    await screen.findByText('还没有简历')
    await user.upload(screen.getByLabelText('选择简历文件'), new File(['java'], 'resume.txt'))
    await user.click(screen.getByRole('button', { name: '上传并分析' }))

    expect(uploadSignal).toBeInstanceOf(AbortSignal)
    view.unmount()
    expect(uploadSignal?.aborted).toBe(true)

    await act(async () => {
      rejectUpload(new Error('late upload failure'))
      await Promise.resolve()
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
