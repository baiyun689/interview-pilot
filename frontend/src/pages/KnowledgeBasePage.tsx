import { useEffect, useRef, useState } from 'react'
import { createKnowledgeBase, listDocuments, listKnowledgeBases, reindexDocument, uploadDocument } from '../api/knowledgeBases'
import { ErrorNotice } from '../components/InterviewUi'
import type { KnowledgeBase, KnowledgeDocument } from '../types/knowledge'

const statusLabel: Record<string, string> = { ACTIVE: '就绪', ARCHIVED: '已归档', DELETING: '删除中' }
const docStatusLabel: Record<string, string> = { PENDING: '待处理', PROCESSING: '索引中', READY: '已就绪', FAILED: '失败', DELETING: '删除中' }

export function KnowledgeBasePage() {
  const owner = useRef(0)
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [expandedBase, setExpandedBase] = useState<string | null>(null)
  const [documents, setDocuments] = useState<Record<string, KnowledgeDocument[]>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>()
  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    const id = ++owner.current
    setLoading(true)
    listKnowledgeBases().then((rows) => { if (owner.current === id) setBases(rows) })
      .catch((e) => { if (owner.current === id) setError(e) })
      .finally(() => { if (owner.current === id) setLoading(false) })
    return () => { owner.current++ }
  }, [])

  async function handleCreate() {
    const name = newName.trim()
    if (!name || creating) return
    setCreating(true)
    try {
      const created = await createKnowledgeBase(name)
      setBases((prev) => [...prev, created])
      setNewName('')
    } catch (e) { setError(e) }
    finally { setCreating(false) }
  }

  async function toggleExpand(baseId: string) {
    if (expandedBase === baseId) { setExpandedBase(null); return }
    setExpandedBase(baseId)
    if (!documents[baseId]) {
      try {
        const docs = await listDocuments(baseId)
        setDocuments((prev) => ({ ...prev, [baseId]: docs }))
      } catch { /* best effort */ }
    }
  }

  async function handleUpload(baseId: string, file: File) {
    try {
      const doc = await uploadDocument(baseId, file)
      setDocuments((prev) => ({ ...prev, [baseId]: [...(prev[baseId] || []), doc] }))
      setBases((prev) => prev.map((b) => b.knowledgeBaseId === baseId ? { ...b, readyDocumentCount: b.readyDocumentCount + (doc.status === 'READY' ? 1 : 0) } : b))
    } catch (e) { setError(e) }
  }

  async function handleReindex(baseId: string, documentId: string) {
    try {
      const doc = await reindexDocument(baseId, documentId)
      setDocuments((prev) => ({
        ...prev,
        [baseId]: (prev[baseId] || []).map((d) => d.documentId === documentId ? doc : d),
      }))
    } catch (e) { setError(e) }
  }

  if (loading) return <p className="page-status" role="status">正在加载知识库…</p>

  return <section>
    <header className="page-header"><p className="eyebrow">Personal knowledge</p><h1>个人知识库</h1><p>上传面试参考文档，让 AI 面试官关联出题。</p></header>
    <ErrorNotice error={error} />

    <form className="kb-create-form" onSubmit={(e) => { e.preventDefault(); handleCreate() }}>
      <input placeholder="知识库名称" value={newName} maxLength={255} onChange={(e) => setNewName(e.target.value)} />
      <button className="button button-primary" disabled={creating || !newName.trim()} type="submit">创建</button>
    </form>

    {bases.length === 0 && <p className="empty-copy">还没有知识库。上传 Markdown、TXT、PDF 或 DOCX 文档开始构建。</p>}

    <ul className="kb-list" aria-label="知识库列表">
      {bases.map((base) => <li className="kb-card" key={base.knowledgeBaseId}>
        <div className="kb-card-header" onClick={() => toggleExpand(base.knowledgeBaseId)}>
          <strong>{base.name}</strong>
          <span className={`status-chip${base.status === 'ACTIVE' ? ' status-enabled' : ''}`}>{statusLabel[base.status] || base.status}</span>
          <small>{base.readyDocumentCount} 篇文档</small>
        </div>
        {expandedBase === base.knowledgeBaseId && <div className="kb-docs">
          <label className="kb-upload-btn">
            上传文档
            <input type="file" hidden accept=".md,.txt,.pdf,.docx" onChange={(e) => { const f = e.target.files?.[0]; if (f) handleUpload(base.knowledgeBaseId, f) }} />
          </label>
          {(documents[base.knowledgeBaseId] || []).map((doc) => <div className="kb-doc-item" key={doc.documentId}>
            <span>{doc.originalFilename || doc.documentId}</span>
            <span className="status-chip">{docStatusLabel[doc.status] || doc.status}</span>
            {doc.status === 'FAILED' && <button className="button button-small" onClick={() => handleReindex(base.knowledgeBaseId, doc.documentId)}>重建索引</button>}
            {doc.failureReason && <small className="error-notice">{doc.failureReason}</small>}
          </div>)}
          {!documents[base.knowledgeBaseId] && <p className="empty-copy">正在加载文档…</p>}
        </div>}
      </li>)}
    </ul>
  </section>
}
