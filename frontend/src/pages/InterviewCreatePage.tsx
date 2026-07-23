import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createInterview, listInterviewSkills } from '../api/interviews'
import { listKnowledgeBases } from '../api/knowledgeBases'
import { listProviders } from '../api/providers'
import { listResumes } from '../api/resumes'
import { ErrorNotice } from '../components/InterviewUi'
import type { AiProvider } from '../types/provider'
import type { ResumeDetail } from '../types/resume'
import type { Difficulty, InterviewSkill } from '../types/interview'
import type { KnowledgeBase } from '../types/knowledge'

export function InterviewCreatePage() {
  const navigate = useNavigate()
  const owner = useRef(0)
  const submitController = useRef<AbortController | null>(null)
  const [resumes, setResumes] = useState<ResumeDetail[]>([])
  const [providers, setProviders] = useState<AiProvider[]>([])
  const [skills, setSkills] = useState<InterviewSkill[]>([])
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([])
  const [selectedKbIds, setSelectedKbIds] = useState<string[]>([])
  const [resumesLoaded, setResumesLoaded] = useState(false)
  const [providersLoaded, setProvidersLoaded] = useState(false)
  const [resumeError, setResumeError] = useState<unknown>()
  const [providerError, setProviderError] = useState<unknown>()
  const [skillError, setSkillError] = useState<unknown>()
  const [submitError, setSubmitError] = useState<unknown>()
  const [submitting, setSubmitting] = useState(false)
  const [values, setValues] = useState({ resumeId: '', skillId: '', jobTitle: '', jdText: '', difficulty: 'MEDIUM' as Difficulty, totalTurnBudget: 8, providerId: '' })

  useEffect(() => {
    const id = ++owner.current
    const controller = new AbortController()
    listResumes(controller.signal).then((rows) => { if (owner.current === id) setResumes(rows.filter((row) => row.status === 'READY')) })
      .catch((error) => { if (owner.current === id) setResumeError(error) })
      .finally(() => { if (owner.current === id) setResumesLoaded(true) })
    listProviders(controller.signal).then((rows) => { if (owner.current === id) setProviders(rows.filter((row) => row.enabled)) })
      .catch((error) => { if (owner.current === id) setProviderError(error) })
      .finally(() => { if (owner.current === id) setProvidersLoaded(true) })
    listInterviewSkills(controller.signal).then((rows) => { if (owner.current === id) setSkills(rows) })
      .catch((error) => { if (owner.current === id) setSkillError(error) })
    listKnowledgeBases(controller.signal).then((rows) => {
      if (owner.current === id) setKnowledgeBases(rows.filter((kb) => kb.readyDocumentCount > 0))
    }).catch(() => { /* silent */ })
    return () => { owner.current++; controller.abort(); submitController.current?.abort() }
  }, [])

  function toggleKb(id: string) {
    setSelectedKbIds((prev) => {
      if (prev.includes(id)) return prev.filter((v) => v !== id)
      if (prev.length >= 5) return prev
      return [...prev, id]
    })
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (submitting) return
    const jobTitle = values.jobTitle.trim(), jdText = values.jdText.trim()
    const custom = values.skillId === 'custom'
    if (!values.providerId || !values.skillId || !jobTitle || (custom && !jdText) || jobTitle.length > 200 || jdText.length > 20_000 || values.totalTurnBudget < 5 || values.totalTurnBudget > 15) {
      setSubmitError(new Error('请完整填写有效的面试信息'))
      return
    }
    const id = owner.current
    const controller = new AbortController(); submitController.current = controller
    setSubmitting(true); setSubmitError(undefined)
    try {
      const resumeId = values.resumeId ? Number(values.resumeId) : null
      const response = await createInterview({ ...values, resumeId, jobTitle, jdText, knowledgeBaseIds: selectedKbIds }, controller.signal)
      if (id === owner.current && response.status === 201) navigate(`/interviews/${response.data.sessionId}`)
    } catch (error) { if (id === owner.current && !(error instanceof DOMException && error.name === 'AbortError')) setSubmitError(error) }
    finally { if (id === owner.current) setSubmitting(false) }
  }

  return <section>
    <header className="page-header"><p className="eyebrow">Adaptive interview</p><h1>开始一场面试</h1><p>基于求职方向和知识库创建自适应文字面试，也可选择简历以获得更个性化的体验。</p></header>
    <form className="interview-form" onSubmit={submit}>
      <label>候选人简历（可选）<select value={values.resumeId} onChange={(e) => setValues({ ...values, resumeId: e.target.value })}><option value="">不使用简历，自由出题</option>{resumes.map((r) => <option key={r.id} value={r.id}>{r.originalFilename}</option>)}</select></label>
      {!resumesLoaded && <p className="empty-copy" role="status">正在加载简历…</p>}
      <ErrorNotice error={resumeError} />
      <label>面试方向<select value={values.skillId} onChange={(e) => {
        const skillId = e.target.value
        const selected = skills.find((skill) => skill.id === skillId)
        setValues({ ...values, skillId, jobTitle: selected && selected.group !== 'CUSTOM' ? selected.displayName : '' })
      }} required><option value="">请选择</option>
        <optgroup label="固定岗位">{skills.filter((s) => s.group === 'JOB').map((s) => <option key={s.id} value={s.id}>{s.displayName}</option>)}</optgroup>
        <optgroup label="专项面试">{skills.filter((s) => s.group === 'SPECIALTY').map((s) => <option key={s.id} value={s.id}>{s.displayName}</option>)}</optgroup>
        {skills.filter((s) => s.group === 'CUSTOM').map((s) => <option key={s.id} value={s.id}>{s.displayName}</option>)}
      </select></label>
      <ErrorNotice error={skillError} />
      {values.skillId && <p className="empty-copy">{skills.find((s) => s.id === values.skillId)?.description}</p>}
      <label>岗位名称<input value={values.jobTitle} maxLength={200} onChange={(e) => setValues({ ...values, jobTitle: e.target.value })} required /></label>
      <label>岗位描述{values.skillId !== 'custom' && '（可选补充）'}<textarea value={values.jdText} maxLength={20_000} rows={8} onChange={(e) => setValues({ ...values, jdText: e.target.value })} required={values.skillId === 'custom'} /></label>
      <div className="form-row"><label>难度<select value={values.difficulty} onChange={(e) => setValues({ ...values, difficulty: e.target.value as Difficulty })}><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
      <label>轮次预算<input type="number" min={5} max={15} value={values.totalTurnBudget} onChange={(e) => setValues({ ...values, totalTurnBudget: Number(e.target.value) })} /></label></div>
      <label>模型<select value={values.providerId} onChange={(e) => setValues({ ...values, providerId: e.target.value })} required><option value="">请选择</option>{providers.map((p) => <option key={p.id} value={p.id}>{p.displayName} · {p.model}</option>)}</select></label>
      {!providersLoaded && <p className="empty-copy" role="status">正在加载模型…</p>}
      <ErrorNotice error={providerError} />
      {providersLoaded && !providerError && providers.length === 0 && <p className="empty-copy">没有可用模型。<Link to="/settings">前往模型设置</Link></p>}

      {knowledgeBases.length > 0 && (
        <fieldset className="kb-select-fieldset">
          <legend>知识库（可选，最多 5 个）</legend>
          <p className="kb-select-hint">选择知识库后，AI 将基于你上传的资料进行针对性提问。</p>
          <div className="kb-select-list">
            {knowledgeBases.map((kb) => (
              <label className={`kb-select-item${selectedKbIds.includes(kb.knowledgeBaseId) ? ' kb-selected' : ''}`} key={kb.knowledgeBaseId}>
                <input
                  type="checkbox"
                  className="visually-hidden"
                  checked={selectedKbIds.includes(kb.knowledgeBaseId)}
                  onChange={() => toggleKb(kb.knowledgeBaseId)}
                />
                <span className="kb-select-name">{kb.name}</span>
                <span className="kb-select-count">{kb.readyDocumentCount} 个文档</span>
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <ErrorNotice error={submitError} />
      <button className="button button-primary" disabled={submitting} type="submit">{submitting ? '创建中…' : '创建并开始面试'}</button>
    </form>
  </section>
}
