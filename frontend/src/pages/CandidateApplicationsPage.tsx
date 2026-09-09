import { useState } from 'react'
import { Link } from 'react-router-dom'
import { hiringWrite, statusLabels, type Application, type Page } from '../api/hiring'
import { ApplicationSnapshot, HiringError, HiringPager, useHiringLoad } from '../components/HiringUi'
import './hiring.css'

export function CandidateApplicationsPage() {
  const [page, setPage] = useState(0)
  const records = useHiringLoad<Page<Application>>(`/api/candidate/applications?page=${page}`)
  const [selected, setSelected] = useState<number | null>(null)
  const [confirm, setConfirm] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function withdraw(application: Application) {
    setBusy(true); setError('')
    try {
      await hiringWrite(`/api/candidate/applications/${application.id}/withdraw`, { version: application.version })
      setSelected(null); setConfirm(null); records.refresh()
    } catch (e) { setError(e instanceof Error ? e.message : '撤回失败') } finally { setBusy(false) }
  }
  return <div className="hiring-page"><header><p className="hiring-eyebrow">CANDIDATE / APPLICATIONS</p><h1>我的投递</h1>
    <p>查看提交的材料与招聘进度。<Link to="/jobs">浏览开放岗位</Link></p></header>
    <HiringError message={error || records.error} />{records.loading && <p>正在读取投递记录…</p>}
    {records.data?.items.length === 0 && <p className="hiring-card">还没有投递记录，先选择一个适合的岗位。</p>}
    {records.data?.items.map(a => <article className="hiring-card" key={a.id}>
      <div className="hiring-row"><div><p className="hiring-eyebrow">{a.organizationName}</p><h2>{a.jobTitle}</h2></div><span className="hiring-badge">{statusLabels[a.status]}</span></div>
      <p>{new Date(a.submittedAt).toLocaleString()} · 第 {a.submissionNo} 次提交</p>
      <div className="hiring-actions"><button onClick={() => setSelected(selected === a.id ? null : a.id)}>查看提交材料</button>
        {!['WITHDRAWN', 'FINISHED'].includes(a.status) && <button disabled={busy} onClick={() => setConfirm(a.id)}>撤回投递</button>}
        {a.status === 'WITHDRAWN' && <Link to={`/jobs/${a.jobId}`}>重新查看岗位</Link>}</div>
      {confirm === a.id && <div role="alert"><p>撤回后企业将停止处理本次投递。确认撤回？</p><div className="hiring-actions">
        <button disabled={busy} onClick={() => void withdraw(a)}>确认撤回</button><button disabled={busy} onClick={() => setConfirm(null)}>保留投递</button></div></div>}
    </article>)}
    <HiringPager page={page} hasMore={!!records.data?.hasMore} change={n => { setPage(n); setSelected(null) }} />
    {selected !== null && <ApplicationSnapshot key={selected} path={`/api/candidate/applications/${selected}`} />}
  </div>
}
