import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { EnterprisePage } from './EnterprisePage'
import { HiringJobDetailPage } from './HiringJobsPage'
import { CandidateApplicationsPage } from './CandidateApplicationsPage'
import { request } from '../api/request'

vi.mock('../api/request', () => ({ request: vi.fn() }))
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ user: { id: 1, email: 'candidate@example.test' } }) }))
const mockedRequest = vi.mocked(request)
const application = { id: 9, organizationId: 1, jobId: 3, organizationName: '示例企业', jobTitle: 'Java 实习', candidateId: 1,
  candidateName: '测试候选人', status: 'SUBMITTED', submissionNo: 1, jobRevision: 2, resumeRevisionId: 7, submittedAt: '2026-09-09T08:00:00Z', version: 4 }
const page = (items: unknown[]) => ({ items, page: 0, hasMore: false })

beforeEach(() => vi.resetAllMocks())
describe('recruitment foundation', () => {
  it('submits the displayed published version and explicitly selected resume', async () => {
    mockedRequest.mockImplementation(async path => {
      if (path === '/api/public/jobs/3') return { id: 3, organizationId: 1, organizationName: '示例企业', title: 'Java 实习', description: '数据库与缓存', location: '上海', employmentType: '实习', publishedRevision: 2 }
      if (path === '/api/resumes') return [{ id: 5, originalFilename: '我的简历.txt' }]
      return application
    })
    render(<MemoryRouter initialEntries={['/jobs/3']}><Routes><Route path="/jobs/:jobId" element={<HiringJobDetailPage />} /></Routes></MemoryRouter>)
    await screen.findByText('Java 实习')
    const user = userEvent.setup()
    await user.selectOptions(screen.getByLabelText('选择简历'), '5')
    await user.click(screen.getByRole('button', { name: '确认提交简历' }))
    await screen.findByRole('status')
    expect(mockedRequest).toHaveBeenCalledWith('/api/candidate/jobs/3/applications', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ resumeId: 5, jobRevision: 2, resubmit: false }),
    }))
  })

  it('withdraws only after confirmation and sends the application version', async () => {
    mockedRequest.mockImplementation(async path => path === '/api/candidate/applications?page=0' ? page([application]) : {})
    render(<MemoryRouter><CandidateApplicationsPage /></MemoryRouter>)
    await screen.findByText('Java 实习')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '撤回投递' }))
    expect(mockedRequest.mock.calls.filter(([path]) => path.includes('/withdraw'))).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: '确认撤回' }))
    await waitFor(() => expect(mockedRequest).toHaveBeenCalledWith('/api/candidate/applications/9/withdraw', expect.objectContaining({ body: '{"version":4}' })))
  })

  it('does not expose member management and job creation controls to interviewers', async () => {
    mockedRequest.mockImplementation(async path => path === '/api/organizations' ? [{ id: 1, name: '示例企业', role: 'INTERVIEWER' }] : page([]))
    render(<MemoryRouter><EnterprisePage /></MemoryRouter>)
    await screen.findByText('面试官')
    expect(screen.queryByRole('button', { name: '团队成员' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '创建岗位' })).not.toBeInTheDocument()
  })

  it('clears previous organization data immediately when switching spaces', async () => {
    let resolveSecond: ((value: unknown) => void) | undefined
    mockedRequest.mockImplementation(async path => {
      if (path === '/api/organizations') return [{ id: 1, name: '甲企业', role: 'ADMIN' }, { id: 2, name: '乙企业', role: 'ADMIN' }]
      if (path.includes('/1/jobs')) return page([{ id: 1, title: '甲企业保密岗位', canManage: true, status: 'DRAFT' }])
      if (path.includes('/2/jobs')) return new Promise(resolve => { resolveSecond = resolve })
      return []
    })
    render(<MemoryRouter><EnterprisePage /></MemoryRouter>)
    await screen.findByText('甲企业保密岗位')
    await userEvent.setup().click(screen.getByRole('button', { name: '乙企业' }))
    expect(screen.queryByText('甲企业保密岗位')).not.toBeInTheDocument()
    resolveSecond?.(page([]))
    await screen.findByText('还没有可访问的岗位。创建岗位或联系管理员授权。')
  })
})
