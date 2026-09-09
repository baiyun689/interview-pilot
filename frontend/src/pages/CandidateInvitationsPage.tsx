import { useState, type FormEvent } from 'react'
import { hiringWrite, type Page } from '../api/hiring'
import { fetchWithAuthRetry } from '../api/request'
import { useNavigate } from 'react-router-dom'
import { HiringError, HiringPager, useHiringLoad } from '../components/HiringUi'
import './hiring.css'
import { CandidateFeedback } from '../components/HiringReviews'

interface Invitation { id: string; organizationName: string; jobTitle: string; roundNo: number; status: string; opensAt: string; latestStartAt: string; closesAt: string; durationMinutes: number; plannedAt: string | null; timezone: string; scheduleRevision: number; version: number }
const labels: Record<string,string> = {ISSUED:'待确认',ACCEPTED:'已安排',STARTED:'面试中',COMPLETED:'已完成',DECLINED:'已拒绝',CANCELLED:'已撤销',EXPIRED:'已过期'}
const date = (value:string) => new Date(value).toLocaleString()
function localInput(value:string) { const d=new Date(value);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16) }

export function CandidateInvitationsPage() {
  const [page,setPage]=useState(0)
  const invitations=useHiringLoad<Page<Invitation>>(`/api/candidate/invitations?page=${page}`)
  return <div className="hiring-page"><header><p className="hiring-eyebrow">MY INTERVIEWS</p><h1>面试邀请</h1><p>确认邀请，为下一场面试安排时间。</p></header>
    <HiringError message={invitations.error}/>{invitations.loading && <p role="status">正在读取邀请…</p>}
    {invitations.data?.items.length===0 && <section className="hiring-card"><h2>暂时没有面试邀请</h2><p>企业发出邀请后，会显示在这里。你可以先到“我的投递”查看申请进度。</p></section>}
    {invitations.data?.items.map(invite=><InvitationCard key={`${invite.id}-${invite.version}`} invite={invite} changed={invitations.refresh}/>)}
    <HiringPager page={page} hasMore={!!invitations.data?.hasMore} change={setPage}/>
  </div>
}

function InvitationCard({invite,changed}:{invite:Invitation;changed:()=>void}) {
  const navigate=useNavigate()
  const [confirmStart,setConfirmStart]=useState(false)
  const [feedback,setFeedback]=useState(false)
  const [editing,setEditing]=useState(false)
  const [planned,setPlanned]=useState(invite.plannedAt?localInput(invite.plannedAt):'')
  const [declining,setDeclining]=useState(false)
  const [busy,setBusy]=useState(false)
  const [error,setError]=useState('')
  const active=['ISSUED','ACCEPTED'].includes(invite.status)
  async function start() {
    if(busy)return;setBusy(true);setError('')
    try {const result=await hiringWrite<{sessionId:string}>(`/api/candidate/invitations/${invite.id}/start`);navigate(`/interviews/${result.sessionId}`)}
    catch(e){setError(e instanceof Error?e.message:'开始面试失败')}finally{setBusy(false)}
  }
  async function downloadCalendar() {
    if (busy) return
    setBusy(true); setError('')
    try {
      const response = await fetchWithAuthRetry(`/api/candidate/invitations/${invite.id}/calendar`)
      if (!response.ok) throw new Error('日历下载失败，请刷新邀请后重试')
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a')
      link.href = url; link.download = 'interview.ics'; document.body.appendChild(link); link.click(); link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) { setError(e instanceof Error ? e.message : '日历下载失败') }
    finally { setBusy(false) }
  }
  async function schedule(e:FormEvent) {
    e.preventDefault();if(busy)return;setBusy(true);setError('')
    try{await hiringWrite(`/api/candidate/invitations/${invite.id}/schedule`,{plannedAt:new Date(planned).toISOString(),version:invite.version},'PUT');changed();setEditing(false)}
    catch(e){setError(e instanceof Error?e.message:'保存失败')}finally{setBusy(false)}
  }
  async function decline(){setBusy(true);setError('');try{await hiringWrite(`/api/candidate/invitations/${invite.id}/decline`,{version:invite.version});changed()}catch(e){setError(e instanceof Error?e.message:'操作失败')}finally{setBusy(false)}}
  return <article className="hiring-card"><div className="hiring-row"><div><span className="hiring-eyebrow">{invite.organizationName}</span><h2>{invite.jobTitle}</h2></div><span className={`hiring-badge ${invite.status==='ACCEPTED'?'hiring-badge-green':''}`}>{labels[invite.status]??invite.status}</span></div>
    <p>第 {invite.roundNo} 轮 · {invite.durationMinutes} 分钟</p>
    <div className="hiring-stages"><div><small>开放时间</small><strong>{date(invite.opensAt)}</strong></div><div><small>最晚开始</small><strong>{date(invite.latestStartAt)}</strong></div></div>
    <p className="hiring-footnote">当前显示时区：{Intl.DateTimeFormat().resolvedOptions().timeZone}。最晚结束时间：{date(invite.closesAt)}。</p>
    {invite.plannedAt && <p className="hiring-progress">计划开始：{date(invite.plannedAt)}</p>}<HiringError message={error}/>
    {invite.plannedAt && <div><button disabled={busy} onClick={()=>void downloadCalendar()}>下载日历</button><p className="hiring-footnote">导入到个人日历后，改期或取消请重新下载并导入；最新安排以此页面为准。</p></div>}
    {invite.status==='ACCEPTED' && <button className="hiring-primary" disabled={busy} onClick={()=>setConfirmStart(true)}>进入面试</button>}
    {['STARTED','COMPLETED'].includes(invite.status) && <button className="hiring-primary" disabled={busy} onClick={()=>void start()}>{invite.status==='STARTED'?'恢复面试':'查看作答记录'}</button>}
    {['COMPLETED','CANCELLED'].includes(invite.status) && <button onClick={()=>setFeedback(!feedback)}>查看企业反馈</button>}
    {feedback && <CandidateFeedback id={invite.id}/>}
    {confirmStart && <div className="hiring-stage-editor"><p>确认开始后将连续计时 {invite.durationMinutes} 分钟，刷新或退出页面不会暂停。请准备好后再开始。</p><div className="hiring-actions"><button disabled={busy} onClick={()=>void start()}>确认开始面试</button><button disabled={busy} onClick={()=>setConfirmStart(false)}>暂不开始</button></div></div>}
    {active && <div className="hiring-actions"><button className="hiring-primary" disabled={busy} onClick={()=>{setEditing(!editing);setDeclining(false)}}>{invite.plannedAt?'调整计划时间':'确认并安排时间'}</button><button disabled={busy} onClick={()=>{setDeclining(true);setEditing(false)}}>拒绝邀请</button></div>}
    {editing && <form onSubmit={e=>void schedule(e)}><label>计划开始时间<input type="datetime-local" required disabled={busy} min={localInput(invite.opensAt)} max={localInput(invite.latestStartAt)} value={planned} onChange={e=>setPlanned(e.target.value)}/></label><p>计划时间用于安排日程；实际开始仍须在企业设置的开放窗口内。</p><button className="hiring-primary" disabled={busy}>{busy?'正在保存…':'保存日程'}</button></form>}
    {declining && <div className="hiring-stage-editor"><p>确认拒绝这次面试邀请？企业将看到拒绝状态。</p><div className="hiring-actions"><button disabled={busy} onClick={()=>void decline()}>确认拒绝</button><button disabled={busy} onClick={()=>setDeclining(false)}>保留邀请</button></div></div>}
  </article>
}
