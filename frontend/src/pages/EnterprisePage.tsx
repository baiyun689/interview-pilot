import { useEffect, useState, type FormEvent } from 'react'
import { ArrowUpRight, BriefcaseBusiness, ClipboardList, FileSearch, Layers3, Plus, Settings2, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { hiringWrite, roleLabels, statusLabels, type Application, type Job, type Member, type Organization, type Page, type Role } from '../api/hiring'
import { ApplicationSnapshot, HiringError, HiringPager, useHiringLoad } from '../components/HiringUi'
import { HiringApplicationAnalysis, HiringSchemes } from '../components/HiringAssessment'
import { EnterpriseKnowledge } from '../components/EnterpriseKnowledge'
import { HiringCampaign } from '../components/HiringCampaign'
import { HiringReviews } from '../components/HiringReviews'
import { NotificationsPage } from './NotificationsPage'
import './hiring.css'

const message = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试'
export function EnterprisePage() {
  const organizations = useHiringLoad<Organization[]>('/api/organizations')
  const [selected, setSelected] = useState<number | null>(null)
  const [name, setName] = useState('')
  const [token, setToken] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const active = organizations.data?.find(o => o.id === selected) ?? organizations.data?.[0]
  async function submit(event: FormEvent, join: boolean) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const org = await hiringWrite<Organization>(join ? '/api/organizations/member-invitations/accept' : '/api/organizations', join ? { token } : { name })
      setSelected(org.id); setName(''); setToken(''); organizations.refresh()
    } catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  return <div className="hiring-page hiring-workspace"><header className="enterprise-page-header"><div><p className="hiring-eyebrow">WORKSPACE / RECRUITMENT</p><h1>企业招聘空间</h1><p>从岗位发布到面试评审，在一个清晰的工作台里推进招聘流程。</p></div><div className="enterprise-header-visual" aria-hidden="true"><BriefcaseBusiness size={28} /><span>Recruitment<br />workspace</span></div></header>
    <HiringError message={error || organizations.error} />
    {organizations.loading && <p>正在读取企业空间…</p>}
    {organizations.data && <>{organizations.data.length > 1 && <div className="hiring-actions">{organizations.data.map(org => <button className={active?.id === org.id ? 'hiring-primary' : ''} key={org.id} onClick={() => setSelected(org.id)}>{org.name}</button>)}</div>}
      {active && <EnterpriseWorkspace key={active.id} organization={active} onMembershipChanged={organizations.refresh} />}
      <details className="hiring-card" open={organizations.data.length === 0}><summary>创建企业或接受成员邀请</summary><div className="hiring-grid">
        <form onSubmit={e => void submit(e, false)}><h3>创建企业</h3><label>企业名称<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label><button disabled={busy} type="submit">创建企业空间</button></form>
        <form onSubmit={e => void submit(e, true)}><h3>加入团队</h3><label>成员邀请码<input required maxLength={128} value={token} onChange={e => setToken(e.target.value)} /></label><p>请使用受邀邮箱对应的账号。</p><button disabled={busy} type="submit">接受邀请</button></form>
      </div></details>
      </>}
  </div>
}

function EnterpriseWorkspace({ organization, onMembershipChanged }: { organization: Organization; onMembershipChanged: () => void }) {
  const [tab, setTab] = useState('jobs')
  const [name, setName] = useState(organization.name)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function rename(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${organization.id}`, { name }, 'PUT'); onMembershipChanged() }
    catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  return <><div className="enterprise-org-header"><div><p className="hiring-eyebrow">CURRENT ORGANIZATION</p><div className="hiring-row"><h2>{organization.name}</h2><span className="hiring-badge">{roleLabels[organization.role]}</span></div></div><p className="enterprise-org-tip">当前身份决定可见的操作范围</p></div>
    <nav className="hiring-tabs" aria-label="企业工作区">
      <button aria-pressed={tab === 'jobs'} onClick={() => setTab('jobs')}><BriefcaseBusiness size={16} aria-hidden />招聘工作台</button>
      <button aria-pressed={tab === 'reviews'} onClick={() => setTab('reviews')}><ClipboardList size={16} aria-hidden />面试与评审</button>
      {organization.role !== 'INTERVIEWER' && <button aria-pressed={tab === 'notifications'} onClick={() => setTab('notifications')}>通知记录</button>}
      {organization.role !== 'INTERVIEWER' && <button aria-pressed={tab === 'knowledge'} onClick={() => setTab('knowledge')}><FileSearch size={16} aria-hidden />企业知识库</button>}
      {organization.role === 'ADMIN' && <><button aria-pressed={tab === 'members'} onClick={() => setTab('members')}><Users size={16} aria-hidden />团队成员</button>
        <button aria-pressed={tab === 'audit'} onClick={() => setTab('audit')}>操作记录</button>
        <button aria-pressed={tab === 'settings'} onClick={() => setTab('settings')}><Settings2 size={16} aria-hidden />企业设置</button></>}
    </nav>
    {tab === 'settings' && organization.role === 'ADMIN' && <section className="hiring-card"><h3>企业信息</h3><form onSubmit={e => void rename(e)}>
      <HiringError message={error} /><label>企业名称<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label><button className="hiring-primary" disabled={busy}>{busy ? '正在保存…' : '保存企业名称'}</button></form></section>}
    {tab === 'jobs' && <EnterpriseJobs organization={organization} />}
    {tab === 'reviews' && <HiringReviews orgId={organization.id} />}
    {tab === 'notifications' && <NotificationsPage orgId={organization.id} />}
    {tab === 'members' && <EnterpriseMembers organization={organization} changed={onMembershipChanged} />}
    {tab === 'audit' && <EnterpriseAudit organizationId={organization.id} />}
    {tab === 'knowledge' && <EnterpriseKnowledge orgId={organization.id} />}
  </>
}

function EnterpriseJobs({ organization }: { organization: Organization }) {
  const [page, setPage] = useState(0)
  const jobs = useHiringLoad<Page<Job>>(`/api/organizations/${organization.id}/jobs?page=${page}`)
  const [editing, setEditing] = useState<Job | 'new' | null>(null)
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null)
  const [activePanel, setActivePanel] = useState<'applications' | 'schemes' | 'campaign'>('applications')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const activeJob = jobs.data?.items.find(job => job.id === selectedJobId) ?? jobs.data?.items[0] ?? null
  useEffect(() => {
    if (jobs.data?.items.length && !jobs.data.items.some(job => job.id === selectedJobId)) setSelectedJobId(jobs.data.items[0].id)
  }, [jobs.data, selectedJobId])
  async function transition(job: Job, action: string) {
    setError(''); setBusy(true)
    try { await hiringWrite(`/api/organizations/${organization.id}/jobs/${job.id}/${action}`, { version: job.version }); jobs.refresh() }
    catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  const publishedCount = jobs.data?.items.filter(job => job.status === 'PUBLISHED').length ?? 0
  const draftCount = jobs.data?.items.filter(job => job.status === 'DRAFT').length ?? 0
  const closedCount = jobs.data?.items.filter(job => job.status === 'CLOSED').length ?? 0
  return <section className="enterprise-jobs"><div className="enterprise-section-header"><div><p className="hiring-eyebrow">RECRUITMENT WORKSPACE</p><h2>岗位管理</h2><p>选择一个岗位，集中处理投递、面试方案和面试批次。</p></div>{organization.role !== 'INTERVIEWER' && <button className="hiring-primary enterprise-create-button" onClick={() => setEditing('new')}><Plus size={17} aria-hidden />创建岗位</button>}</div>
    <HiringError message={error || jobs.error} />
    <div className="enterprise-stat-grid" aria-label="岗位概览"><div className="enterprise-stat-card"><span>全部岗位</span><strong>{jobs.data?.items.length ?? '—'}</strong><small>当前可访问岗位</small></div><div className="enterprise-stat-card enterprise-stat-card-active"><span>招聘中</span><strong>{jobs.data ? publishedCount : '—'}</strong><small>对外接受投递</small></div><div className="enterprise-stat-card"><span>草稿</span><strong>{jobs.data ? draftCount : '—'}</strong><small>待发布版本</small></div><div className="enterprise-stat-card"><span>已关闭</span><strong>{jobs.data ? closedCount : '—'}</strong><small>暂停新投递</small></div></div>
    {editing && <JobEditor key={editing === 'new' ? 'new' : editing.id} orgId={organization.id} job={editing === 'new' ? null : editing} done={() => { setEditing(null); jobs.refresh() }} cancel={() => setEditing(null)} />}
    {jobs.loading && <p className="enterprise-loading">正在读取岗位…</p>}
    {jobs.data?.items.length === 0 && <div className="hiring-card enterprise-empty"><BriefcaseBusiness size={28} aria-hidden /><h3>还没有可访问的岗位</h3><p>还没有可访问的岗位。创建岗位或联系管理员授权。</p></div>}
    {jobs.data && jobs.data.items.length > 0 && <div className="enterprise-jobs-layout"><aside className="enterprise-job-list" aria-label="岗位列表"><div className="enterprise-subheading"><h3>我的岗位</h3><span>{jobs.data.items.length} 个</span></div>{jobs.data.items.map(job => <button className={`enterprise-job-item${activeJob?.id === job.id ? ' is-active' : ''}`} key={job.id} aria-pressed={activeJob?.id === job.id} onClick={() => { setSelectedJobId(job.id); setActivePanel('applications') }}><span className="enterprise-job-item-main"><strong>{job.title}</strong><small>{job.location} · {job.employmentType}</small></span><span className={`enterprise-status enterprise-status-${job.status.toLowerCase()}`}>{statusLabels[job.status]}</span></button>)}</aside>
      {activeJob && <article className="enterprise-job-workspace"><div className="enterprise-job-heading"><div><div className="enterprise-heading-line"><span className="enterprise-job-icon"><BriefcaseBusiness size={19} aria-hidden /></span><div><p className="hiring-eyebrow">JOB WORKSPACE</p><h3>岗位工作区</h3></div><span className={`enterprise-status enterprise-status-${activeJob.status.toLowerCase()}`}>{statusLabels[activeJob.status]}</span></div><p className="enterprise-job-meta">{activeJob.title} · {activeJob.location} · {activeJob.employmentType} · 已发布版本 {activeJob.publishedRevision || '未发布'}</p></div><div className="hiring-actions enterprise-job-actions">{activeJob.canManage && activeJob.status !== 'CLOSED' && <><button disabled={busy} onClick={() => setEditing(activeJob)}>编辑草稿</button><button className="hiring-primary" disabled={busy} onClick={() => void transition(activeJob, 'publish')}>{activeJob.publishedRevision ? '发布新版本' : '发布岗位'}</button></>}{activeJob.status === 'PUBLISHED' && <button disabled={busy} onClick={() => void transition(activeJob, 'close')}>关闭新投递</button>}{activeJob.status === 'PUBLISHED' && <Link className="enterprise-public-link" to={`/jobs/${activeJob.id}`} target="_blank" rel="noreferrer">公开岗位页面 <ArrowUpRight size={15} aria-hidden /></Link>}</div></div>
        <div className="enterprise-job-tabs" role="tablist" aria-label={`${activeJob.title}工作区`}><button role="tab" aria-selected={activePanel === 'applications'} onClick={() => setActivePanel('applications')}><FileSearch size={17} aria-hidden /><span>投递管理</span><small>查看候选人材料</small></button><button role="tab" aria-selected={activePanel === 'schemes'} disabled={!activeJob.canManage} onClick={() => setActivePanel('schemes')}><Layers3 size={17} aria-hidden /><span>面试方案</span><small>配置题目与评分标准</small></button><button role="tab" aria-selected={activePanel === 'campaign'} disabled={!activeJob.canManage} onClick={() => setActivePanel('campaign')}><ClipboardList size={17} aria-hidden /><span>面试批次</span><small>准备题卡并发出邀请</small></button></div>
        <div className="enterprise-panel-body">{activePanel === 'applications' && <EnterpriseApplications key={activeJob.id} orgId={organization.id} jobId={activeJob.id} />}{activePanel === 'schemes' && <HiringSchemes key={activeJob.id} orgId={organization.id} jobId={activeJob.id} />}{activePanel === 'campaign' && <HiringCampaign key={activeJob.id} orgId={organization.id} jobId={activeJob.id} />}</div>
        {organization.role === 'ADMIN' && <div className="enterprise-permissions"><JobAssignments orgId={organization.id} jobId={activeJob.id} /></div>}
      </article>}</div>}
    <HiringPager page={page} hasMore={!!jobs.data?.hasMore} change={n => { setPage(n); setSelectedJobId(null); setActivePanel('applications') }} />
  </section>
}

function JobEditor({ orgId, job, done, cancel }: { orgId: number; job: Job | null; done: () => void; cancel: () => void }) {
  const [title, setTitle] = useState(job?.title ?? '')
  const [description, setDescription] = useState(job?.description ?? '')
  const [location, setLocation] = useState(job?.location ?? '')
  const [employmentType, setEmploymentType] = useState(job?.employmentType ?? '实习')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function save(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${orgId}/jobs${job ? `/${job.id}` : ''}`, { title, description, location, employmentType, version: job?.version ?? 0 }, job ? 'PUT' : 'POST'); done() }
    catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  return <form className="hiring-card" onSubmit={e => void save(e)}><h3>{job ? '编辑岗位草稿' : '创建岗位草稿'}</h3>
    <p>保存草稿不会改变公开页面。发布新版本后，新投递使用新要求，历史投递保持原版本。</p><HiringError message={error} />
    <label>岗位名称<input required maxLength={200} value={title} onChange={e => setTitle(e.target.value)} /></label>
    <div className="hiring-grid"><label>工作地点<input required maxLength={120} value={location} onChange={e => setLocation(e.target.value)} /></label>
      <label>岗位类型<select value={employmentType} onChange={e => setEmploymentType(e.target.value)}><option>实习</option><option>校招全职</option><option>社招全职</option></select></label></div>
    <label>岗位要求与考察能力<textarea required rows={9} maxLength={20000} value={description} onChange={e => setDescription(e.target.value)} /></label>
    <div className="hiring-actions"><button className="hiring-primary" disabled={busy}>保存草稿</button><button type="button" disabled={busy} onClick={cancel}>取消</button></div>
  </form>
}

function EnterpriseApplications({ orgId, jobId }: { orgId: number; jobId: number }) {
  const [page, setPage] = useState(0)
  const records = useHiringLoad<Page<Application>>(`/api/organizations/${orgId}/jobs/${jobId}/applications?page=${page}`)
  const [selected, setSelected] = useState<number | null>(null)
  return <section className="hiring-card"><h2>岗位投递</h2><HiringError message={records.error} />
    {records.loading && <p>正在读取投递…</p>}{records.data?.items.length === 0 && <p>还没有候选人投递。</p>}
    {records.data?.items.map(a => <div className="hiring-row hiring-list-row" key={a.id}><div><strong>{a.candidateName}</strong><p>{statusLabels[a.status]} · {new Date(a.submittedAt).toLocaleString()}</p></div>
      <button onClick={() => setSelected(selected === a.id ? null : a.id)}>查看材料</button></div>)}
    <HiringPager page={page} hasMore={!!records.data?.hasMore} change={n => { setPage(n); setSelected(null) }} />
    {selected && <div key={selected}><HiringApplicationAnalysis orgId={orgId} applicationId={selected} status={records.data?.items.find(a => a.id === selected)?.status ?? ''} /><ApplicationSnapshot path={`/api/organizations/${orgId}/applications/${selected}`} /></div>}
  </section>
}

function EnterpriseMembers({ organization, changed }: { organization: Organization; changed: () => void }) {
  const members = useHiringLoad<Member[]>(`/api/organizations/${organization.id}/members`)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('RECRUITER')
  const [invitation, setInvitation] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function invite(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setInvitation('')
    try {
      const result = await hiringWrite<{ token: string }>(`/api/organizations/${organization.id}/member-invitations`, { email, role })
      setInvitation(result.token); setEmail('')
    } catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  async function update(member: Member, role: Role, active: boolean) {
    setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${organization.id}/members/${member.userId}`, { role, active }, 'PUT'); members.refresh(); changed() }
    catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  return <section className="hiring-card"><h2>团队成员</h2><HiringError message={error || members.error} />
    <form onSubmit={e => void invite(e)}><div className="hiring-grid"><label>受邀邮箱<input type="email" required maxLength={320} value={email} onChange={e => setEmail(e.target.value)} /></label>
      <label>角色<select value={role} onChange={e => setRole(e.target.value as Role)}>{Object.entries(roleLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label></div>
      <button disabled={busy}>生成成员邀请</button></form>
    {invitation && <div role="status"><p>邀请码有效期 7 天，请交给受邀者在企业空间中领取。</p><input aria-label="生成的邀请码" readOnly value={invitation} onFocus={e => e.target.select()} /></div>}
    {members.data?.map(member => <div className="hiring-row hiring-list-row" key={member.userId}><div><strong>{member.displayName}</strong><p>{member.email} · {member.active ? '有效' : '已停用'}</p></div>
      <div className="hiring-actions"><select aria-label={`${member.displayName}的角色`} disabled={busy} value={member.role} onChange={e => void update(member, e.target.value as Role, member.active)}>{Object.entries(roleLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
        <button disabled={busy} onClick={() => void update(member, member.role, !member.active)}>{member.active ? '停用' : '恢复'}</button></div></div>)}
  </section>
}

function JobAssignments({ orgId, jobId }: { orgId: number; jobId: number }) {
  const [open, setOpen] = useState(false)
  const members = useHiringLoad<Member[]>(open ? `/api/organizations/${orgId}/members` : null)
  const assignments = useHiringLoad<number[]>(open ? `/api/organizations/${orgId}/jobs/${jobId}/assignments` : null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function toggle(userId: number, assigned: boolean) {
    setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${orgId}/jobs/${jobId}/assignments/${userId}`, {}, assigned ? 'DELETE' : 'PUT'); assignments.refresh() }
    catch (e) { setError(message(e)) } finally { setBusy(false) }
  }
  return <details onToggle={e => setOpen(e.currentTarget.open)}><summary>岗位授权</summary><HiringError message={error || members.error || assignments.error} />
    <p>企业管理员拥有全部岗位权限；招聘人员需获得岗位授权。面试官的候选人评审指派将独立管理。</p>
    {members.data?.filter(m => m.active && m.role === 'RECRUITER').map(m => <label className="hiring-checkbox" key={m.userId}>
      <input type="checkbox" disabled={busy || !assignments.data} checked={assignments.data?.includes(m.userId) ?? false} onChange={() => void toggle(m.userId, assignments.data?.includes(m.userId) ?? false)} />{m.displayName}</label>)}
  </details>
}

function EnterpriseAudit({ organizationId }: { organizationId: number }) {
  const [page, setPage] = useState(0)
  const records = useHiringLoad<{ id: number; actorId: number; action: string; resourceId: number; createdAt: string }[]>(`/api/organizations/${organizationId}/audit?page=${page}`)
  const labels: Record<string, string> = { ORGANIZATION_CREATED: '创建企业', MEMBER_INVITED: '邀请成员', MEMBER_JOINED: '成员加入', MEMBER_UPDATED: '调整成员权限', JOB_CREATED: '创建岗位', JOB_DRAFT_UPDATED: '编辑岗位草稿', JOB_PUBLISHED: '发布岗位版本', JOB_CLOSED: '关闭岗位', JOB_ACCESS_GRANTED: '授予岗位权限', JOB_ACCESS_REVOKED: '撤销岗位权限' }
  return <section className="hiring-card"><h2>操作记录</h2><HiringError message={records.error} />
    {records.data?.map(e => <p key={e.id}>{labels[e.action] ?? e.action} · 操作者 {e.actorId} · 记录 {e.resourceId} · {new Date(e.createdAt).toLocaleString()}</p>)}
    <HiringPager page={page} hasMore={records.data?.length === 25} change={setPage} /></section>
}
