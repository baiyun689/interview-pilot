import { render,screen,waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach,expect,it,vi } from 'vitest'
import { HiringReviews,CandidateFeedback } from './HiringReviews'
import { NotificationsPage } from '../pages/NotificationsPage'
import { request } from '../api/request'
vi.mock('../api/request',()=>({request:vi.fn()}))
const mocked=vi.mocked(request)
beforeEach(()=>vi.resetAllMocks())
const content={dimensions:[{name:'技术',evaluation:'较好'}],evidenceTurns:[1],notes:'企业内部秘密',decision:'NEXT_ROUND'}
const detail={interview:{invitationId:'i1',candidateName:'林同学',jobTitle:'Java',roundNo:1,status:'COMPLETED'},sessionStatus:'COMPLETED',aiReport:null,turns:[{turnNo:1,question:'缓存一致性',answer:'先更新数据库',evaluationStatus:'FAILED'}],reviews:[{id:1,reviewerId:7,reviewerName:'招聘负责人',status:'SUBMITTED',version:2,content}],history:[{id:9,reviewerId:7,revision:1,content,submittedAt:'2026-09-09T09:00:00Z'}],assignments:[],publications:[],currentUserId:7,canPublish:true}
it('requires a separate public message and preview before publishing a versioned review',async()=>{
  mocked.mockImplementation(async(path)=>path.endsWith('reviews?page=0')?{items:[detail.interview],hasMore:false}:path.endsWith('/reviewers')?[]:detail)
  render(<MemoryRouter><HiringReviews orgId={1}/></MemoryRouter>)
  const user=userEvent.setup();await user.click(await screen.findByRole('button',{name:'查看面试与评审'}))
  await user.selectOptions(await screen.findByLabelText('采用的人工评审'),'9')
  expect(screen.getByLabelText('公开反馈')).toHaveValue('')
  await user.type(screen.getByLabelText('公开反馈'),'欢迎参加下一轮')
  await user.click(screen.getByRole('button',{name:'预览公开反馈'}))
  expect(mocked.mock.calls.some(([p])=>p.endsWith('/publish'))).toBe(false)
  await user.click(screen.getByRole('button',{name:'确认发布反馈'}))
  await waitFor(()=>expect(mocked).toHaveBeenCalledWith('/api/organizations/1/reviews/i1/publish',expect.objectContaining({body:JSON.stringify({revision:0,reviewRevisionId:9,decision:'NEXT_ROUND',feedback:'欢迎参加下一轮'})})))
})
it('candidate feedback requests only the candidate endpoint',async()=>{
  mocked.mockResolvedValue([{revision:1,decision:'NEXT_ROUND',feedback:'欢迎参加下一轮',publishedAt:'2026-09-09T09:00:00Z'}])
  render(<CandidateFeedback id="i1"/>);await screen.findByText('欢迎参加下一轮')
  expect(mocked.mock.calls.every(([p])=>p==='/api/candidate/invitations/i1/feedback')).toBe(true)
  expect(screen.queryByText('企业内部秘密')).not.toBeInTheDocument()
})
it('unknown SMTP outcome requires explicit retry confirmation and sends its version',async()=>{
  mocked.mockResolvedValue({items:[{id:5,title:'邀请',message:'面试邀请',mailStatus:'UNKNOWN',version:4}],hasMore:false})
  render(<MemoryRouter><NotificationsPage orgId={1}/></MemoryRouter>);const user=userEvent.setup()
  await user.click(await screen.findByRole('button',{name:'重试邮件'}))
  expect(mocked.mock.calls.some(([p])=>p.endsWith('/retry'))).toBe(false)
  await user.click(screen.getByRole('button',{name:'确认重发'}))
  await waitFor(()=>expect(mocked).toHaveBeenCalledWith('/api/organizations/1/notifications/5/retry',expect.objectContaining({body:'{"version":4}'})))
})
