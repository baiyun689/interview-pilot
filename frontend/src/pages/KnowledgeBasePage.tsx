import { useCallback, useEffect, useRef, useState } from 'react'
import { BookOpen, FileText, RefreshCw, Upload, ChevronDown, ChevronRight, Trash2 } from 'lucide-react'
import { createKnowledgeBase, deleteDocument, deleteKnowledgeBase, listDocuments, listKnowledgeBases, reindexDocument, uploadDocument } from '../api/knowledgeBases'
import { ErrorNotice } from '../components/InterviewUi'
import type { KnowledgeBase, KnowledgeDocument } from '../types/knowledge'

interface KnowledgeBasePageProps {
  pollIntervalMs?: number
}

export function KnowledgeBasePage({ pollIntervalMs = 3000 }: KnowledgeBasePageProps) {
  const owner = useRef(0)
  const mounted = useRef(true)
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState<unknown>()
  const [createName, setCreateName] = useState('')
  const [createError, setCreateError] = useState<unknown>()
  const [creating, setCreating] = useState(false)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [docs, setDocs] = useState<Record<string, KnowledgeDocument[]>>({})
  const [docsLoading, setDocsLoading] = useState<Set<string>>(new Set())
  const [docsError, setDocsError] = useState<Record<string, unknown>>({})
  const [uploading, setUploading] = useState<Set<string>>(new Set())
  const [uploadError, setUploadError] = useState<Record<string, unknown>>({})
  const [deleting, setDeleting] = useState<Set<string>>(new Set())
  const [deletingBases, setDeletingBases] = useState<Set<string>>(new Set())

  useEffect(() => () => { mounted.current = false }, [])

  const load = useCallback(() => {
    const id = ++owner.current
    const controller = new AbortController()
    listKnowledgeBases(controller.signal)
      .then((rows) => { if (owner.current === id) setBases(rows) })
      .catch((error) => { if (owner.current === id) setLoadError(error) })
      .finally(() => { if (owner.current === id) setLoaded(true) })
    return () => { owner.current++; controller.abort() }
  }, [])

  useEffect(() => load(), [load])

  const refreshDocuments = useCallback(async (baseId: string, signal?: AbortSignal) => {
    const rows = await listDocuments(baseId, signal)
    if (!mounted.current) return
    setDocs((prev) => ({ ...prev, [baseId]: rows }))
    setBases((prev) => prev.map((base) => base.knowledgeBaseId === baseId
      ? { ...base, readyDocumentCount: rows.filter((doc) => doc.status === 'READY').length }
      : base))
  }, [])

  useEffect(() => {
    const activeBaseIds = [...expanded].filter((baseId) =>
      docs[baseId]?.some((doc) => doc.status === 'PENDING'
        || doc.status === 'PROCESSING'
        || doc.status === 'DELETING'),
    )
    if (activeBaseIds.length === 0) return undefined

    const controller = new AbortController()
    const timer = window.setInterval(() => {
      activeBaseIds.forEach((baseId) => {
        refreshDocuments(baseId, controller.signal).catch((error) => {
          if (!controller.signal.aborted && mounted.current) {
            setDocsError((prev) => ({ ...prev, [baseId]: error }))
          }
        })
      })
    }, pollIntervalMs)
    return () => { controller.abort(); window.clearInterval(timer) }
  }, [docs, expanded, pollIntervalMs, refreshDocuments])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    const name = createName.trim()
    if (!name || name.length > 255 || creating) return
    const id = ++owner.current
    setCreating(true); setCreateError(undefined)
    try {
      const created = await createKnowledgeBase(name)
      if (owner.current === id) {
        setBases((prev) => [created, ...prev])
        setCreateName('')
      }
    } catch (error) { if (owner.current === id) setCreateError(error) }
    finally { if (owner.current === id) setCreating(false) }
  }

  async function toggleExpand(baseId: string) {
    const next = new Set(expanded)
    if (next.has(baseId)) { next.delete(baseId); setExpanded(next); return }
    next.add(baseId); setExpanded(next)
    if (docs[baseId]) return
    const id = ++owner.current
    setDocsLoading((prev) => new Set(prev).add(baseId))
    try {
      const rows = await listDocuments(baseId)
      if (owner.current === id) {
        setDocs((prev) => ({ ...prev, [baseId]: rows }))
        setBases((prev) => prev.map((base) => base.knowledgeBaseId === baseId
          ? { ...base, readyDocumentCount: rows.filter((doc) => doc.status === 'READY').length }
          : base))
      }
    } catch (error) {
      if (owner.current === id) setDocsError((prev) => ({ ...prev, [baseId]: error }))
    } finally {
      if (owner.current === id) setDocsLoading((prev) => { const n = new Set(prev); n.delete(baseId); return n })
    }
  }

  async function handleUpload(baseId: string, file: File) {
    const id = ++owner.current
    setUploading((prev) => new Set(prev).add(baseId))
    setUploadError((prev) => { const n = { ...prev }; delete n[baseId]; return n })
    try {
      const resp = await uploadDocument(baseId, file)
      if (owner.current === id && resp.status === 202) {
        setDocs((prev) => {
          const existing = prev[baseId] ?? []
          return { ...prev, [baseId]: [resp.data, ...existing] }
        })
        void refreshDocuments(baseId)
      }
    } catch (error) { if (owner.current === id) setUploadError((prev) => ({ ...prev, [baseId]: error })) }
    finally {
      if (owner.current === id) setUploading((prev) => { const n = new Set(prev); n.delete(baseId); return n })
    }
  }

  async function handleReindex(baseId: string, documentId: string) {
    const id = ++owner.current
    setUploading((prev) => new Set(prev).add(documentId))
    try {
      const resp = await reindexDocument(baseId, documentId)
      if (owner.current === id && resp.status === 202) {
        setDocs((prev) => {
          const rows = prev[baseId]
          if (!rows) return prev
          return { ...prev, [baseId]: rows.map((d) => d.documentId === documentId ? resp.data : d) }
        })
        void refreshDocuments(baseId)
      }
    } catch { /* ignore */ }
    finally {
      if (owner.current === id) setUploading((prev) => { const n = new Set(prev); n.delete(documentId); return n })
    }
  }

  async function handleDelete(baseId: string, doc: KnowledgeDocument) {
    if (deleting.has(doc.documentId) || !window.confirm(`删除文档「${doc.originalFilename || doc.documentId}」？`)) {
      return
    }
    setDeleting((prev) => new Set(prev).add(doc.documentId))
    setDocs((prev) => {
      const rows = prev[baseId]
      if (!rows) return prev
      return { ...prev, [baseId]: rows.map((d) => d.documentId === doc.documentId ? { ...d, status: 'DELETING' } : d) }
    })
    try {
      await deleteDocument(baseId, doc.documentId)
      if (!mounted.current) return
      setDocs((prev) => {
        const rows = prev[baseId]
        if (!rows) return prev
        return { ...prev, [baseId]: rows.filter((d) => d.documentId !== doc.documentId) }
      })
      setBases((prev) => prev.map((base) => base.knowledgeBaseId === baseId
        ? { ...base, readyDocumentCount: Math.max(0, base.readyDocumentCount - (doc.status === 'READY' ? 1 : 0)) }
        : base))
    } catch (error) {
      if (mounted.current) {
        setUploadError((prev) => ({ ...prev, [baseId]: error }))
        void refreshDocuments(baseId)
      }
    } finally {
      if (mounted.current) setDeleting((prev) => { const n = new Set(prev); n.delete(doc.documentId); return n })
    }
  }

  async function removeBase(base: KnowledgeBase) {
    if (deletingBases.has(base.knowledgeBaseId)
        || !window.confirm(`删除知识库「${base.name}」及其全部 ${base.readyDocumentCount} 个就绪文档？已创建的面试记录会保留。`)) {
      return
    }
    setDeletingBases((prev) => new Set(prev).add(base.knowledgeBaseId))
    try {
      await deleteKnowledgeBase(base.knowledgeBaseId)
      if (!mounted.current) return
      setBases((prev) => prev.filter((item) => item.knowledgeBaseId !== base.knowledgeBaseId))
      setExpanded((prev) => { const next = new Set(prev); next.delete(base.knowledgeBaseId); return next })
      setDocs((prev) => { const next = { ...prev }; delete next[base.knowledgeBaseId]; return next })
    } catch (error) {
      if (mounted.current) setLoadError(error)
    } finally {
      if (mounted.current) setDeletingBases((prev) => { const next = new Set(prev); next.delete(base.knowledgeBaseId); return next })
    }
  }

  function statusLabel(status: string): string {
    switch (status) {
      case 'PENDING': return '待处理'
      case 'PROCESSING': return '索引中'
      case 'READY': return '就绪'
      case 'FAILED': return '失败'
      case 'DELETING': return '删除中'
      case 'DELETED': return '已删除'
      default: return status
    }
  }

  function statusClass(status: string): string {
    switch (status) {
      case 'READY': return 'status-chip status-enabled'
      case 'PROCESSING': case 'PENDING': return 'status-chip status-disabled'
      case 'FAILED': case 'DELETING': case 'DELETED': return 'status-chip status-disabled'
      default: return 'status-chip status-disabled'
    }
  }

  return (
    <section>
      <header className="page-header">
        <p className="eyebrow">Knowledge base</p>
        <h1>知识库</h1>
        <p>上传文档建立知识库，面试时 AI 将基于你的资料进行针对性提问。</p>
      </header>

      <form className="kb-create-form" onSubmit={handleCreate}>
        <input
          className="kb-create-input"
          value={createName}
          maxLength={255}
          onChange={(e) => setCreateName(e.target.value)}
          placeholder="输入知识库名称，例如「Java 后端面试题库」"
          required
        />
        <button className="button button-primary" disabled={creating || !createName.trim()} type="submit">
          {creating ? '创建中…' : '创建知识库'}
        </button>
        <ErrorNotice error={createError} />
      </form>

      {!loaded && <p className="empty-copy" role="status">正在加载知识库…</p>}
      <ErrorNotice error={loadError} />

      {loaded && !loadError && bases.length === 0 && (
        <p className="empty-copy">还没有知识库，在上方创建一个吧。</p>
      )}

      {bases.length > 0 && (
        <div className="kb-list">
          {bases.map((base) => {
            const isExpanded = expanded.has(base.knowledgeBaseId)
            const isLoadingDocs = docsLoading.has(base.knowledgeBaseId)
            const isUploading = uploading.has(base.knowledgeBaseId)
            const docList = docs[base.knowledgeBaseId]
            const docErr = docsError[base.knowledgeBaseId]
            const upErr = uploadError[base.knowledgeBaseId]

            return (
              <div className="kb-card" key={base.knowledgeBaseId}>
                <div className="kb-card-header" onClick={() => toggleExpand(base.knowledgeBaseId)} role="button" tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') toggleExpand(base.knowledgeBaseId) }}>
                  <span className="kb-chevron">{isExpanded ? <ChevronDown size={18} /> : <ChevronRight size={18} />}</span>
                  <BookOpen size={20} className="kb-icon" />
                  <div className="kb-card-info">
                    <h3>{base.name}</h3>
                    <span className="kb-meta">{base.readyDocumentCount} 个就绪文档 · 创建于 {new Date(base.createdAt).toLocaleDateString('zh-CN')}</span>
                  </div>
                  <button
                    className="button button-danger"
                    disabled={deletingBases.has(base.knowledgeBaseId)}
                    onClick={(e) => { e.stopPropagation(); void removeBase(base) }}
                    type="button"
                  >
                    <Trash2 size={14} /> {deletingBases.has(base.knowledgeBaseId) ? '删除中' : '删除'}
                  </button>
                </div>

                {isExpanded && (
                  <div className="kb-docs">
                    <label className="kb-upload-zone">
                      <Upload size={18} />
                      <span>{isUploading ? '上传中…' : '上传文档（PDF / TXT / Markdown）'}</span>
                      <input
                        type="file"
                        accept=".pdf,.txt,.md,.markdown"
                        className="visually-hidden"
                        disabled={isUploading}
                        onChange={(e) => {
                          const file = e.target.files?.[0]
                          if (file) handleUpload(base.knowledgeBaseId, file)
                          e.target.value = ''
                        }}
                      />
                    </label>
                    <ErrorNotice error={upErr} />

                    {isLoadingDocs && <p className="empty-copy" role="status">正在加载文档…</p>}
                    <ErrorNotice error={docErr} />

                    {!isLoadingDocs && !docErr && (!docList || docList.length === 0) && (
                      <p className="empty-copy">暂无文档，上传一个吧。</p>
                    )}

                    {docList && docList.length > 0 && (
                      <ul className="kb-doc-list">
                        {docList.map((doc) => (
                          <li className="kb-doc-item" key={doc.documentId}>
                            <FileText size={16} className="kb-doc-icon" />
                            <span className="kb-doc-name">{doc.originalFilename || doc.documentId}</span>
                            <span className={statusClass(doc.status)}>{statusLabel(doc.status)}</span>
                            {doc.chunkCount > 0 && <span className="kb-doc-chunks">{doc.chunkCount} 片段</span>}
                            {doc.failureReason && <span className="kb-doc-error">{doc.failureReason}</span>}
                            <button
                              className="button button-secondary kb-reindex-btn"
                              disabled={uploading.has(doc.documentId) || doc.status === 'PROCESSING' || doc.status === 'PENDING' || doc.status === 'DELETING'}
                              onClick={() => handleReindex(base.knowledgeBaseId, doc.documentId)}
                              type="button"
                            >
                              <RefreshCw size={14} /> 重建索引
                            </button>
                            <button
                              className="button button-danger kb-reindex-btn"
                              disabled={deleting.has(doc.documentId) || doc.status === 'DELETING'}
                              onClick={() => handleDelete(base.knowledgeBaseId, doc)}
                              type="button"
                            >
                              <Trash2 size={14} /> 删除
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
