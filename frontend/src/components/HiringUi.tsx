import { useEffect, useState } from 'react'
import { request } from '../api/request'
import type { ApplicationDetail } from '../api/hiring'
import { statusLabels } from '../api/hiring'

export function useHiringLoad<T>(path: string | null) {
  const [result, setResult] = useState<{ path: string | null; data: T | null }>({ path: null, data: null })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [revision, refresh] = useState(0)
  useEffect(() => {
    let active = true
    const controller = new AbortController()
    setError('')
    setLoading(!!path)
    if (path) void request<T>(path, { signal: controller.signal }).then(data => {
      if (active) setResult({ path, data })
    }).catch(e => {
      if (active) { setResult({ path, data: null }); setError(e instanceof Error ? e.message : '加载失败') }
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false; controller.abort() }
  }, [path, revision])
  return { data: result.path === path ? result.data : null, error, loading, refresh: () => refresh(n => n + 1) }
}

export function HiringError({ message }: { message: string }) {
  return message ? <p className="hiring-error" role="alert">{message}</p> : null
}
export function HiringPager({ page, hasMore, change }: { page: number; hasMore: boolean; change: (page: number) => void }) {
  if (page === 0 && !hasMore) return null
  return <nav className="hiring-actions hiring-pagination" aria-label="分页"><button type="button" disabled={page === 0} onClick={() => change(page - 1)}>上一页</button>
    <span aria-live="polite">第 {page + 1} 页</span><button type="button" disabled={!hasMore} onClick={() => change(page + 1)}>下一页</button></nav>
}
export function ApplicationSnapshot({ path }: { path: string }) {
  const { data, loading, error } = useHiringLoad<ApplicationDetail>(path)
  return <section className="hiring-card"><h3>投递材料快照</h3><HiringError message={error} />
    {loading && <p>正在读取材料…</p>}
    {data && <><p>第 {data.application.submissionNo} 次提交 · 岗位版本 {data.application.jobRevision}</p>
      <h4>{data.resumeFilename}</h4><pre className="hiring-prose">{data.resumeText}</pre>
      <details><summary>投递时的岗位要求</summary><pre className="hiring-prose">{data.jobDescription}</pre></details>
      <h4>流程记录</h4>{data.events.map((event, i) => <p key={i}>{statusLabels[event.action] ?? event.action} · 第 {event.submissionNo} 次 · {new Date(event.createdAt).toLocaleString()}</p>)}
    </>}
  </section>
}
