import { useEffect, useState, type FormEvent } from 'react'
import { request } from '../api/request'
import { hiringWrite } from '../api/hiring'
import { HiringError, useHiringLoad } from './HiringUi'

interface Base { id: string; name: string }
interface Document { id: string; filename: string; status: string; revision: number; activeRevision: number; chunkCount: number; error: string | null; deletionStatus: string | null }
const statuses: Record<string, string> = { PENDING: '待处理', PROCESSING: '正在建立索引', READY: '可用于检索', FAILED: '索引失败', DELETING: '删除处理中', DELETED: '已删除' }
export function EnterpriseKnowledge({ orgId }: { orgId: number }) {
  const bases = useHiringLoad<Base[]>(`/api/organizations/${orgId}/knowledge`)
  const [name, setName] = useState('')
  const [selected, setSelected] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const active = bases.data?.find(b => b.id === selected) ?? bases.data?.[0]
  async function create(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try { const base = await hiringWrite<Base>(`/api/organizations/${orgId}/knowledge`, { name }); setSelected(base.id); setName(''); bases.refresh() }
    catch (e) { setError(e instanceof Error ? e.message : '创建失败') } finally { setBusy(false) }
  }
  return <section className="hiring-card"><h2>企业知识库</h2><p>集中管理面试参考资料。资料仅供本企业授权人员访问。</p><HiringError message={error || bases.error} />
    <details open={bases.data?.length === 0}><summary>创建知识库</summary><form onSubmit={e => void create(e)}><label>知识库名称<input required maxLength={255} value={name} onChange={e => setName(e.target.value)} /></label><button disabled={busy}>创建知识库</button></form></details>
    <div className="hiring-actions">{bases.data?.map(base => <button key={base.id} className={base.id === active?.id ? 'hiring-primary' : ''} onClick={() => setSelected(base.id)}>{base.name}</button>)}</div>
    {active && <KnowledgeDocuments key={active.id} orgId={orgId} base={active} />}
  </section>
}
function KnowledgeDocuments({ orgId, base }: { orgId: number; base: Base }) {
  const root = `/api/organizations/${orgId}/knowledge/${base.id}/documents`
  const documents = useHiringLoad<Document[]>(root)
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [deleting, setDeleting] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState<{ status: string; failureReason: string | null; chunks: { pointId: string; filename: string; content: string; score: number }[] } | null>(null)
  const pending = documents.data?.some(d => d.status === 'PROCESSING' || (d.status === 'DELETING' && d.deletionStatus !== 'FAILED'))
  useEffect(() => { if (!pending) return; const id = window.setInterval(documents.refresh, 4000); return () => window.clearInterval(id) }, [pending, root])
  async function run(operation: () => Promise<unknown>) {
    setBusy(true); setError('')
    try { await operation(); documents.refresh(); setDeleting(null) }
    catch (e) { setError(e instanceof Error ? e.message : '操作失败') } finally { setBusy(false) }
  }
  async function upload(event: FormEvent) {
    event.preventDefault(); if (!file) return
    if (file.size > 8 * 1024 * 1024) { setError('文件不能超过 8 MiB'); return }
    const form = new FormData(); form.append('file', file)
    await run(() => request(root, { method: 'POST', body: form }))
  }
  async function retrieve(event: FormEvent) {
    event.preventDefault(); setSearch(null)
    await run(async () => { setSearch(await hiringWrite(`/api/organizations/${orgId}/knowledge/search`, { knowledgeBaseIds: [base.id], query })) })
  }
  return <><HiringError message={error || documents.error} /><form className="hiring-upload" onSubmit={e => void upload(e)}><label>上传面试资料<input aria-label="上传面试资料" type="file" accept=".pdf,.txt,.md,.docx" onChange={e => setFile(e.target.files?.[0] ?? null)} /></label><p>支持 PDF、TXT、Markdown、DOCX，单个文件不超过 8 MiB。</p><button disabled={busy || !file}>上传并建立索引</button></form>
    {documents.data?.map(document => <div className="hiring-list-row" key={document.id}><div className="hiring-row"><div><strong>{document.filename}</strong><p>{statuses[document.status]} · 当前版本 {document.revision} · {document.chunkCount} 个片段</p>{document.error && <HiringError message={document.error} />}</div><div className="hiring-actions">
      {['READY', 'FAILED'].includes(document.status) && <button disabled={busy} onClick={() => void run(() => hiringWrite(`${root}/${document.id}/reindex`))}>重建索引</button>}
      {!['PROCESSING', 'DELETING'].includes(document.status) && <button disabled={busy} onClick={() => setDeleting(document.id)}>删除</button>}
      {document.deletionStatus === 'FAILED' && <button disabled={busy} onClick={() => void run(() => hiringWrite(`${root}/${document.id}`, {}, 'DELETE'))}>重试删除</button>}</div></div>
      {deleting === document.id && <div role="alert"><p>确认删除此资料？被已发布面试引用的资料会保留。</p><div className="hiring-actions"><button disabled={busy} onClick={() => void run(() => hiringWrite(`${root}/${document.id}`, {}, 'DELETE'))}>确认删除</button><button onClick={() => setDeleting(null)}>取消</button></div></div>}
    </div>)}
    <details><summary>检查检索效果</summary><form onSubmit={e => void retrieve(e)}><label>输入一个面试问题<input required maxLength={1000} value={query} onChange={e => setQuery(e.target.value)} /></label><button disabled={busy}>查看参考片段</button></form>
      {search && <div><p>{search.status === 'RETRIEVED' ? '找到以下参考资料' : search.status === 'NO_MATCH' ? '没有找到匹配资料' : '检索服务暂不可用'}</p>{search.chunks.map(chunk => <blockquote className="hiring-search-result" key={chunk.pointId}><strong>{chunk.filename}</strong><p>{chunk.content}</p></blockquote>)}</div>}
    </details></>
}
