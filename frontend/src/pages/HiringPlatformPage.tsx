import { useState } from 'react'
import { hiringWrite } from '../api/hiring'
import { HiringError, HiringPager, useHiringLoad } from '../components/HiringUi'
import './hiring.css'

export function HiringPlatformPage() {
  const access = useHiringLoad<{ allowed: boolean }>('/api/platform/access')
  const [page, setPage] = useState(0)
  const organizations = useHiringLoad<{ id: number; name: string; active: boolean }[]>(access.data?.allowed ? `/api/platform/organizations?page=${page}` : null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function toggle(id: number, active: boolean) {
    setBusy(true); setError('')
    try { await hiringWrite(`/api/platform/organizations/${id}/status`, { active }, 'PUT'); organizations.refresh() }
    catch (e) { setError(e instanceof Error ? e.message : '操作失败') } finally { setBusy(false) }
  }
  return <div className="hiring-page"><header><p className="hiring-eyebrow">PLATFORM / OPERATIONS</p><h1>平台管理</h1><p>管理企业使用状态。此页面不展示候选人的简历或回答正文。</p></header>
    <HiringError message={error || access.error || organizations.error} />
    {access.loading && <p>正在验证平台权限…</p>}
    {access.data && !access.data.allowed && <p role="alert">当前账号没有平台管理权限。</p>}
    {organizations.data?.map(o => <article className="hiring-card hiring-row" key={o.id}><div><h2>{o.name}</h2><p>{o.active ? '正常使用' : '已停用'}</p></div><button disabled={busy} onClick={() => void toggle(o.id, !o.active)}>{o.active ? '停用企业' : '恢复企业'}</button></article>)}
    {access.data?.allowed && <HiringPager page={page} hasMore={organizations.data?.length === 25} change={setPage} />}
  </div>
}
