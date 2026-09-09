import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft, ArrowRight, BriefcaseBusiness, Building2, CheckCircle2, FileText, MapPin } from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { hiringWrite, type Application, type Job, type Page } from '../api/hiring'
import type { ResumeDetail } from '../types/resume'
import { HiringError, HiringPager, useHiringLoad } from '../components/HiringUi'
import './hiring.css'

export function HiringJobsPage() {
  const [page, setPage] = useState(0)
  const { data, error, loading } = useHiringLoad<Page<Job>>(`/api/public/jobs?page=${page}`)
  return <div className="hiring-page careers-page"><header className="careers-hero"><div><span className="careers-kicker"><BriefcaseBusiness size={18} aria-hidden="true"/>开放岗位</span><h1>找到适合你的下一站</h1>
    <p>从一份感兴趣的岗位，开启下一段职业旅程。</p></div><Link className="careers-button careers-button-light" to="/candidate/applications"><FileText size={18} aria-hidden="true"/>我的投递<ArrowRight size={18} aria-hidden="true"/></Link></header>
    <div className="careers-section-heading"><h2>正在招聘</h2><span>选择岗位，了解详情后投递</span></div>
    <HiringError message={error} />{loading && <p role="status" className="careers-empty">正在加载岗位…</p>}
    <div className="hiring-grid careers-grid">{data?.items.map(job => <article className="hiring-card careers-job-card" key={job.id}>
      <div className="careers-company"><span className="careers-company-icon"><Building2 size={22} aria-hidden="true"/></span><span>{job.organizationName}</span></div>
      <h2>{job.title}</h2><JobTags job={job}/>
      <div className="careers-summary"><p className="hiring-excerpt">{job.description}</p></div>
      <Link className="careers-button careers-button-primary" to={`/jobs/${job.id}`} aria-label={`查看岗位：${job.title}`}>查看岗位<ArrowRight size={18} aria-hidden="true"/></Link></article>)}</div>
    {data?.items.length === 0 && <div className="careers-empty"><BriefcaseBusiness size={32} aria-hidden="true"/><h2>暂时没有开放岗位</h2><p>新的机会发布后，会出现在这里。</p></div>}
    <HiringPager page={page} hasMore={!!data?.hasMore} change={setPage} /></div>
}

function JobTags({job}:{job:Job}) {
  return <div className="careers-tags"><span><MapPin size={16} aria-hidden="true"/>{job.location || '地点待确认'}</span><span><BriefcaseBusiness size={16} aria-hidden="true"/>{job.employmentType || '招聘岗位'}</span></div>
}

export function HiringJobDetailPage() {
  const { jobId } = useParams()
  return <HiringJobDetail key={jobId} jobId={jobId} />
}

function HiringJobDetail({ jobId }: { jobId: string | undefined }) {
  const { user } = useAuth()
  const job = useHiringLoad<Job>(`/api/public/jobs/${jobId}`)
  const resumes = useHiringLoad<ResumeDetail[]>(user ? '/api/resumes' : null)
  const [resumeId, setResumeId] = useState('')
  const [resubmit, setResubmit] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  async function apply() {
    if (!job.data || !resumeId) return
    setBusy(true); setError('')
    try {
      await hiringWrite<Application>(`/api/candidate/jobs/${jobId}/applications`, {
        resumeId: Number(resumeId), jobRevision: job.data.publishedRevision, resubmit,
      }); setSuccess(true)
    } catch (e) { setError(e instanceof Error ? e.message : '投递失败') } finally { setBusy(false) }
  }
  return <div className="hiring-page careers-page"><Link className="careers-button careers-button-outline careers-back" to="/jobs"><ArrowLeft size={18} aria-hidden="true"/>返回岗位列表</Link>
    <HiringError message={job.error || error || resumes.error} />{job.loading && <p>正在读取岗位…</p>}
    {job.data && <><header className="careers-detail-header"><div className="careers-company"><Building2 size={20} aria-hidden="true"/>{job.data.organizationName}</div><h1>{job.data.title}</h1>
      <JobTags job={job.data}/></header><div className="careers-detail-layout">
      <section className="hiring-card careers-description"><h2>岗位介绍</h2><pre className="hiring-prose">{job.data.description}</pre></section>
      <section className="hiring-card careers-apply"><span className="careers-apply-icon"><FileText size={24} aria-hidden="true"/></span><h2>{success?'投递成功':'投递此岗位'}</h2>{!user ? <><p>登录后，选择一份简历即可投递。</p><Link className="careers-button careers-button-primary" to="/login" state={{from:{pathname:`/jobs/${jobId}`}}}>登录后投递<ArrowRight size={18} aria-hidden="true"/></Link></> : success ?
        <div className="careers-success" role="status"><CheckCircle2 size={24} aria-hidden="true"/><p>简历已送达企业，可随时查看进度。</p><Link className="careers-button careers-button-primary" to="/candidate/applications">查看我的投递<ArrowRight size={18} aria-hidden="true"/></Link></div> : <>
          <p>选择一份简历，让企业了解你。</p>
          <label>选择简历<select value={resumeId} onChange={e => setResumeId(e.target.value)} disabled={busy}>
            <option value="">请选择</option>{resumes.data?.map(r => <option key={r.id} value={r.id}>{r.originalFilename}</option>)}</select></label>
          <Link className="careers-button careers-button-outline" to="/resumes"><FileText size={18} aria-hidden="true"/>上传或管理简历</Link>
          <div className="careers-application-note">投递后会保留这份简历，后续修改不会影响本次申请。</div>
          <label className="hiring-checkbox careers-resubmit"><input type="checkbox" checked={resubmit} onChange={e => setResubmit(e.target.checked)} disabled={busy} />此前已撤回，本次重新投递</label>
          <button className="hiring-primary" disabled={busy || !resumeId} onClick={() => void apply()}>{busy ? '正在投递…' : '确认提交简历'}</button>
        </>}</section></div></>}
  </div>
}
