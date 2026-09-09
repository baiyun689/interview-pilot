import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CandidateInvitationsPage } from './CandidateInvitationsPage'
import { request, fetchWithAuthRetry } from '../api/request'
import { MemoryRouter } from 'react-router-dom'

vi.mock('../api/request',()=>({request:vi.fn(),fetchWithAuthRetry:vi.fn()}))
const mocked=vi.mocked(request)
const invite={id:'invite-one',organizationName:'演示企业',jobTitle:'Java 实习',roundNo:1,status:'ISSUED',opensAt:'2027-01-01T00:00:00Z',latestStartAt:'2027-01-02T00:00:00Z',closesAt:'2027-01-02T00:30:00Z',durationMinutes:30,plannedAt:null,timezone:'Asia/Shanghai',scheduleRevision:0,version:3}
beforeEach(()=>{vi.resetAllMocks();mocked.mockImplementation(async path=>path==='/api/candidate/invitations?page=0'?{items:[invite],page:0,hasMore:false}:{})})
describe('candidate invitations',()=>{
  it('downloads the authenticated calendar snapshot and releases the object URL',async()=>{
    mocked.mockResolvedValue({items:[{...invite,status:'ACCEPTED',plannedAt:'2027-01-01T02:00:00Z'}],page:0,hasMore:false})
    vi.mocked(fetchWithAuthRetry).mockResolvedValue(new Response('BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'))
    const create=vi.fn(()=> 'blob:calendar'); const revoke=vi.fn()
    vi.stubGlobal('URL',Object.assign(URL,{createObjectURL:create,revokeObjectURL:revoke}))
    const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
    try {
      render(<MemoryRouter><CandidateInvitationsPage/></MemoryRouter>);await screen.findByRole('button',{name:'下载日历'})
      await userEvent.setup().click(screen.getByRole('button',{name:'下载日历'}))
      expect(fetchWithAuthRetry).toHaveBeenCalledWith('/api/candidate/invitations/invite-one/calendar')
      expect(create).toHaveBeenCalled();expect(click).toHaveBeenCalled()
      await waitFor(()=>expect(revoke).toHaveBeenCalledWith('blob:calendar'),{timeout:2000})
      expect(screen.getByText(/改期或取消请重新下载/)).toBeInTheDocument()
    } finally {click.mockRestore();vi.unstubAllGlobals()}
  })
  it('requires explicit confirmation before declining an invitation',async()=>{
    render(<MemoryRouter><CandidateInvitationsPage/></MemoryRouter>);await screen.findByText('Java 实习')
    const user=userEvent.setup();await user.click(screen.getByRole('button',{name:'拒绝邀请'}))
    expect(mocked.mock.calls.some(([path])=>path.endsWith('/decline'))).toBe(false)
    await user.click(screen.getByRole('button',{name:'确认拒绝'}))
    await waitFor(()=>expect(mocked).toHaveBeenCalledWith('/api/candidate/invitations/invite-one/decline',expect.objectContaining({body:'{"version":3}'})))
  })
  it('does not expose scheduling actions on a cancelled invitation',async()=>{
    mocked.mockResolvedValue({items:[{...invite,status:'CANCELLED'}],page:0,hasMore:false})
    render(<MemoryRouter><CandidateInvitationsPage/></MemoryRouter>);await screen.findByText('已撤销')
    expect(screen.queryByRole('button',{name:'确认并安排时间'})).not.toBeInTheDocument()
    expect(screen.queryByRole('button',{name:'拒绝邀请'})).not.toBeInTheDocument()
  })
})
