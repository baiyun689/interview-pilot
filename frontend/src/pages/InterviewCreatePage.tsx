import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createInterview, listInterviewPresets } from '../api/interviews'
import { listKnowledgeBases } from '../api/knowledgeBases'
import { listProviders } from '../api/providers'
import { listResumes } from '../api/resumes'
import { ErrorNotice } from '../components/InterviewUi'
import type { AiProvider } from '../types/provider'
import type { ResumeDetail } from '../types/resume'
import type { Difficulty, InterviewPreset, InterviewSize, JobSourceType } from '../types/interview'
import type { KnowledgeBase } from '../types/knowledge'

export function InterviewCreatePage() {
  const navigate = useNavigate()
  const owner = useRef(0)
  const [resumes, setResumes] = useState<ResumeDetail[]>([])
  const [providers, setProviders] = useState<AiProvider[]>([])
  const [presets, setPresets] = useState<InterviewPreset[]>([])
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [selectedKbIds, setSelectedKbIds] = useState<string[]>([])
  const [loadError, setLoadError] = useState<unknown>()
  const [submitError, setSubmitError] = useState<unknown>()
  const [submitting, setSubmitting] = useState(false)
  const [values, setValues] = useState({
    resumeId: '', sourceType: 'PRESET' as JobSourceType, presetId: '',
    jobTitle: '', jobDescription: '', difficulty: 'MEDIUM' as Difficulty,
    interviewSize: 'STANDARD' as InterviewSize, providerId: '',
  })

  useEffect(() => {
    const id = ++owner.current
    const controller = new AbortController()
    Promise.all([
      listResumes(controller.signal), listProviders(controller.signal),
      listInterviewPresets(controller.signal), listKnowledgeBases(controller.signal),
    ]).then(([resumeRows, providerRows, presetRows, baseRows]) => {
      if (owner.current !== id) return
      setResumes(resumeRows.filter((row) => row.status === 'READY'))
      setProviders(providerRows.filter((row) => row.enabled))
      setPresets(presetRows)
      setKnowledgeBases(baseRows.filter((base) => base.readyDocumentCount > 0))
      setValues((old) => ({
        ...old,
        presetId: old.presetId || presetRows[0]?.id || '',
        providerId: old.providerId || providerRows.find((row) => row.defaultProvider)?.id || providerRows[0]?.id || '',
      }))
    }).catch((error) => {
      if (owner.current === id && !(error instanceof DOMException && error.name === 'AbortError')) setLoadError(error)
    })
    return () => { owner.current++; controller.abort() }
  }, [])

  function toggleKb(id: string) {
    setSelectedKbIds((current) => current.includes(id)
      ? current.filter((value) => value !== id)
      : current.length < 5 ? [...current, id] : current)
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (submitting) return
    const title = values.jobTitle.trim(), description = values.jobDescription.trim()
    if (!values.providerId || (values.sourceType === 'PRESET' && !values.presetId)
      || (values.sourceType === 'CUSTOM' && (!title || !description))) {
      setSubmitError(new Error('请完整填写有效的岗位信息'))
      return
    }
    setSubmitting(true); setSubmitError(undefined)
    try {
      const response = await createInterview({
        resumeId: values.resumeId ? Number(values.resumeId) : null,
        jobSource: values.sourceType === 'PRESET'
          ? { type: 'PRESET', presetId: values.presetId }
          : { type: 'CUSTOM', jobTitle: title, jobDescription: description },
        difficulty: values.difficulty,
        interviewSize: values.interviewSize,
        providerId: values.providerId,
        knowledgeBaseIds: selectedKbIds,
      })
      if (response.status === 202) navigate(`/interviews/${response.data.sessionId}`)
    } catch (error) { setSubmitError(error) }
    finally { setSubmitting(false) }
  }

  const selectedPreset = presets.find((preset) => preset.id === values.presetId)
  return <section>
    <header className="page-header"><h1>创建 Java 后端面试</h1><p>先异步准备完整题库，准备完成后由你显式开始。面试过程中不进行即时评分。</p></header>
    <form className="interview-form" onSubmit={submit}>
      <label>候选人简历（可选）<select value={values.resumeId} onChange={(event) => setValues({ ...values, resumeId: event.target.value })}><option value="">不使用简历</option>{resumes.map((resume) => <option key={resume.id} value={resume.id}>{resume.originalFilename}</option>)}</select></label>
      <fieldset><legend>岗位来源</legend><div className="form-row">
        <label><input type="radio" checked={values.sourceType === 'PRESET'} onChange={() => setValues({ ...values, sourceType: 'PRESET' })} /> 预设岗位</label>
        <label><input type="radio" checked={values.sourceType === 'CUSTOM'} onChange={() => setValues({ ...values, sourceType: 'CUSTOM' })} /> 自定义 JD</label>
      </div></fieldset>
      {values.sourceType === 'PRESET' ? <>
        <label>预设岗位<select value={values.presetId} onChange={(event) => setValues({ ...values, presetId: event.target.value })}>{presets.map((preset) => <option key={preset.id} value={preset.id}>{preset.displayName}</option>)}</select></label>
        {selectedPreset && <div className="state-card"><strong>{selectedPreset.jobTitle}</strong><p>{selectedPreset.description}</p><p className="empty-copy">岗位内容由服务端固化，创建页不可修改。</p></div>}
      </> : <>
        <label>岗位名称<input value={values.jobTitle} maxLength={200} onChange={(event) => setValues({ ...values, jobTitle: event.target.value })} required /></label>
        <label>完整 JD<textarea value={values.jobDescription} maxLength={20_000} rows={10} onChange={(event) => setValues({ ...values, jobDescription: event.target.value })} required /></label>
      </>}
      <div className="form-row">
        <label>难度<select value={values.difficulty} onChange={(event) => setValues({ ...values, difficulty: event.target.value as Difficulty })}><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
        <label>面试规模<select value={values.interviewSize} onChange={(event) => setValues({ ...values, interviewSize: event.target.value as InterviewSize })}><option value="QUICK">快速面试 · 6 轮</option><option value="STANDARD">标准面试 · 9 轮</option><option value="DEEP">深度面试 · 12 轮</option></select></label>
      </div>
      <label>模型<select value={values.providerId} onChange={(event) => setValues({ ...values, providerId: event.target.value })} required><option value="">请选择</option>{providers.map((provider) => <option key={provider.id} value={provider.id}>{provider.displayName} · {provider.model}</option>)}</select></label>
      {!providers.length && !loadError && <p className="empty-copy">没有可用模型。<Link to="/settings">前往模型设置</Link></p>}
      {knowledgeBases.length > 0 && <fieldset className="kb-select-fieldset"><legend>知识库（可选，最多 5 个）</legend><p className="kb-select-hint">知识库只作为技术参考，RAG 不可用不会阻断题库准备。</p><div className="kb-select-list">{knowledgeBases.map((base) => <label className={`kb-select-item${selectedKbIds.includes(base.knowledgeBaseId) ? ' kb-selected' : ''}`} key={base.knowledgeBaseId}><input type="checkbox" className="visually-hidden" checked={selectedKbIds.includes(base.knowledgeBaseId)} onChange={() => toggleKb(base.knowledgeBaseId)} /><span className="kb-select-name">{base.name}</span><span className="kb-select-count">{base.readyDocumentCount} 个文档</span></label>)}</div></fieldset>}
      <ErrorNotice error={loadError || submitError} />
      <button className="button button-primary" disabled={submitting} type="submit">{submitting ? '正在创建…' : '创建并准备题库'}</button>
    </form>
  </section>
}
