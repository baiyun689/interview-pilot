import { useCallback, useEffect, useRef, useState } from 'react'
import { FileUp, UploadCloud } from 'lucide-react'
import { Link } from 'react-router-dom'
import { listResumes, uploadResume } from '../api/resumes'
import { ApiClientError } from '../api/request'
import { ResumeStatusBadge } from '../components/ResumeStatusBadge'
import type { ResumeDetail } from '../types/resume'

const MAX_FILE_SIZE = 10 * 1024 * 1024
const ALLOWED_EXTENSIONS = ['.pdf', '.docx', '.txt']

function validateFile(file: File): string | null {
  if (file.size === 0) return '文件不能为空'
  if (file.size > MAX_FILE_SIZE) return '文件不能超过 10 MiB'
  if (!ALLOWED_EXTENSIONS.some((extension) => file.name.toLowerCase().endsWith(extension))) {
    return '仅支持 PDF、DOCX、TXT 文件'
  }
  return null
}

function traceMessage(error: unknown): { message: string; traceId: string | null } {
  if (error instanceof ApiClientError) return { message: error.message, traceId: error.traceId }
  return { message: '操作失败，请稍后重试', traceId: null }
}

function fileSize(size: number) {
  if (size < 1024) return `${size} B`
  return `${(size / 1024 / 1024).toFixed(2)} MiB`
}

export function ResumeListPage() {
  const [resumes, setResumes] = useState<ResumeDetail[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null)
  const [notice, setNotice] = useState<{ message: string; resumeId: number } | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const loadGeneration = useRef(0)
  const lifecycleGeneration = useRef(0)
  const mounted = useRef(false)
  const activeUpload = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    if (!mounted.current) return
    const lifecycle = lifecycleGeneration.current
    const current = ++loadGeneration.current
    try {
      const next = await listResumes()
      if (!mounted.current || lifecycleGeneration.current !== lifecycle || loadGeneration.current !== current) return
      setResumes(next)
      setError(null)
    } catch (loadError) {
      if (!mounted.current || lifecycleGeneration.current !== lifecycle || loadGeneration.current !== current) return
      setError(traceMessage(loadError))
    } finally {
      if (mounted.current && lifecycleGeneration.current === lifecycle && loadGeneration.current === current) {
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    mounted.current = true
    lifecycleGeneration.current += 1
    void load()
    return () => {
      mounted.current = false
      lifecycleGeneration.current += 1
      loadGeneration.current += 1
      activeUpload.current?.abort()
      activeUpload.current = null
    }
  }, [load])

  function choose(file: File | undefined) {
    setNotice(null)
    if (!file) return
    const validation = validateFile(file)
    if (validation) {
      setSelectedFile(null)
      setError({ message: validation, traceId: null })
      if (inputRef.current) inputRef.current.value = ''
      return
    }
    setError(null)
    setSelectedFile(file)
  }

  async function submit() {
    if (!selectedFile || uploading || !mounted.current) return
    const lifecycle = lifecycleGeneration.current
    const controller = new AbortController()
    activeUpload.current = controller
    setUploading(true)
    setError(null)
    setNotice(null)
    try {
      const response = await uploadResume(selectedFile, controller.signal)
      if (!mounted.current || lifecycleGeneration.current !== lifecycle) return
      const duplicate = response.status === 200 && response.data.duplicate
      setNotice({
        message: duplicate ? '相同简历已存在，已复用原有分析' : '简历已上传，AI 分析已开始',
        resumeId: response.data.resumeId,
      })
      setSelectedFile(null)
      if (inputRef.current) inputRef.current.value = ''
      await load()
    } catch (uploadError) {
      if (!mounted.current || lifecycleGeneration.current !== lifecycle) return
      setError(traceMessage(uploadError))
    } finally {
      if (mounted.current && lifecycleGeneration.current === lifecycle) setUploading(false)
      if (activeUpload.current === controller) activeUpload.current = null
    }
  }

  return (
    <section>
      <header className="page-header">
        <p className="eyebrow">RESUME PROFILE</p>
        <h1>简历</h1>
        <p>上传一份简历，生成面试所需的结构化候选人画像。</p>
      </header>

      <div className="resume-upload-card">
        <div
          className="resume-dropzone"
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => { event.preventDefault(); choose(event.dataTransfer.files[0]) }}
        >
          <UploadCloud size={30} aria-hidden />
          <strong>拖放简历到这里，或点击选择文件</strong>
          <span>支持 PDF、DOCX、TXT，最大 10 MiB</span>
          <label className="button button-secondary" htmlFor="resume-file">选择文件</label>
          <input
            ref={inputRef}
            className="visually-hidden"
            id="resume-file"
            aria-label="选择简历文件"
            type="file"
            accept=".pdf,.docx,.txt"
            onChange={(event) => choose(event.target.files?.[0])}
          />
        </div>
        {selectedFile && (
          <div className="selected-file">
            <FileUp size={20} aria-hidden />
            <span><strong>{selectedFile.name}</strong><small>{fileSize(selectedFile.size)}</small></span>
            <button className="button button-primary" disabled={uploading} onClick={() => void submit()}>
              {uploading ? '正在上传' : '上传并分析'}
            </button>
          </div>
        )}
        {notice && (
          <div className="success-notice" role="status">
            {notice.message} · <Link to={`/resumes/${notice.resumeId}`}>查看分析详情</Link>
          </div>
        )}
        {error && <div className="error-notice" role="alert"><p>{error.message}</p>{error.traceId && <small>追踪编号：{error.traceId}</small>}</div>}
      </div>

      <div className="section-heading"><h2>历史简历</h2><span>{resumes.length} 份</span></div>
      {loading ? <p role="status" className="page-status">正在加载简历</p> : resumes.length === 0 ? (
        <div className="state-card resume-empty"><h2>还没有简历</h2><p>上传 PDF、DOCX 或 TXT 文件，AI 会提取技能、项目与面试关注点。</p></div>
      ) : (
        <div className="resume-list">
          {resumes.map((resume) => (
            <article className="resume-list-card" key={resume.id}>
              <div><h3>{resume.originalFilename}</h3><time dateTime={resume.createdAt}>{new Date(resume.createdAt).toLocaleString('zh-CN')}</time></div>
              <ResumeStatusBadge status={resume.status} />
              <Link className="detail-link" to={`/resumes/${resume.id}`}>查看详情</Link>
            </article>
          ))}
        </div>
      )}
    </section>
  )
}
