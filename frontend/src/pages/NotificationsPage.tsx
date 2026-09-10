import { useState } from 'react'
import { Link } from 'react-router-dom'
import { hiringWrite, type Page } from '../api/hiring'
import { HiringError, HiringPager, useHiringLoad } from '../components/HiringUi'
import './hiring.css'

interface Notice {id:number;title:string;message:string;invitationId:string|null;visibleAt:string|null;readAt:string|null;mailStatus:string;error:string|null;attempts:number;version:number}
const labels:Record<string,string>={PENDING:'等待发送',SENDING:'正在发送',ACCEPTED_BY_PROVIDER:'邮件服务商已接收',FAILED:'发送失败',UNKNOWN:'接收情况未知',DISABLED:'邮件通道未启用',SKIPPED:'安排失效，已跳过'}
export function NotificationsPage({orgId}:{orgId?:number}) {
  const [page,setPage]=useState(0),[busy,setBusy]=useState(false),[error,setError]=useState(''),[confirm,setConfirm]=useState<number|null>(null)
  const base=orgId?`/api/organizations/${orgId}/notifications`:'/api/notifications'
  const data=useHiringLoad<Page<Notice>>(`${base}?page=${page}`)
  async function act(n:Notice) {setBusy(true);setError('');try{await hiringWrite(`${base}/${n.id}/${orgId?'retry':'read'}`,{version:n.version},orgId?'POST':'PUT');setConfirm(null);data.refresh()}catch(e){setError(e instanceof Error?e.message:'操作失败')}finally{setBusy(false)}}
  return <div className="hiring-page careers-page"><header className="hiring-row"><div><h2>{orgId?'通知投递记录':'通知中心'}</h2><p>{orgId?'站内消息与邮件投递分别记录，邮件失败不会影响面试邀请。':'邀请、日程变更和企业反馈都在这里。'}</p></div><button onClick={data.refresh}>刷新</button></header><HiringError message={error||data.error}/>
    {data.loading&&<p role="status">正在加载通知…</p>}{data.data?.items.length===0&&<section className="hiring-card">暂无通知。</section>}
    {data.data?.items.map(n=><article key={n.id} className="hiring-card"><div className="hiring-row"><h3>{n.title}</h3><span className="hiring-badge">{orgId?labels[n.mailStatus]:n.readAt?'已读':'未读'}</span></div><p>{n.message}</p>{orgId&&<p>{n.error??`发送尝试 ${n.attempts} 次`}</p>}<div className="hiring-actions">{!orgId&&<><Link className="careers-button careers-button-primary" to="/candidate/invitations">查看面试邀请</Link>{!n.readAt&&<button disabled={busy} onClick={()=>void act(n)}>标记已读</button>}</>}
      {orgId&&['FAILED','UNKNOWN','DISABLED'].includes(n.mailStatus)&&<>{confirm===n.id?<div><p>重发可能导致候选人收到重复邮件，请确认后继续。</p><button disabled={busy} onClick={()=>void act(n)}>确认重发</button><button onClick={()=>setConfirm(null)}>取消</button></div>:<button disabled={busy} onClick={()=>n.mailStatus==='UNKNOWN'?setConfirm(n.id):void act(n)}>重试邮件</button>}</>}</div></article>)}
    <HiringPager page={page} hasMore={!!data.data?.hasMore} change={setPage}/>
  </div>
}
