import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { createInterview, listInterviewPresets } from '../api/interviews'
import { listKnowledgeBases } from '../api/knowledgeBases'
import { listProviders } from '../api/providers'
import { listResumes } from '../api/resumes'
import { fetchVoiceCapabilities } from '../api/voice'
import { ErrorNotice } from '../components/InterviewUi'
import type { AiProvider } from '../types/provider'
import type { ResumeDetail } from '../types/resume'
import type { Difficulty, InterviewMode, InterviewPreset, InterviewSize, JobSourceType } from '../types/interview'
import type { KnowledgeBase } from '../types/knowledge'
import type { VoiceCapabilities } from '../types/voice'

export function InterviewCreatePage() {
  const navigate = useNavigate()
  const owner = useRef(0)
  const voiceOwner = useRef(0)
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
    interviewMode: 'TEXT' as InterviewMode,
  })
  const [voiceCapabilities, setVoiceCapabilities] = useState<VoiceCapabilities | null>(null)
  const [voiceCapabilitiesError, setVoiceCapabilitiesError] = useState<unknown>()

  // 语音能力探测（计划 §13.1）：失败只影响语音选项的可用性，不阻断表单其余部分。
  // 未返回时禁用并提示检测中；失败时禁用并给出通用原因；返回后按 enabled 开关。
  const voiceDisabled = !voiceCapabilities || !voiceCapabilities.enabled
  const voiceReason = voiceCapabilities
    ? voiceCapabilities.enabled ? '' : '语音面试未启用：未配置语音识别 Provider'
    : voiceCapabilitiesError ? '语音面试未启用：语音服务暂时不可用' : '正在检测语音能力…'

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

  // 语音能力探测（计划 §13.1）：失败只影响语音选项的可用性，不阻断表单其余部分。
  // 使用独立的 voiceOwner：不能共享主加载的 owner，否则会作废主请求的异步回执。
  useEffect(() => {
    const id = ++voiceOwner.current
    const controller = new AbortController()
    fetchVoiceCapabilities(controller.signal)
      .then((caps) => { if (voiceOwner.current === id) setVoiceCapabilities(caps) })
      .catch((error) => {
        if (voiceOwner.current === id && !(error instanceof DOMException && error.name === 'AbortError')) {
          setVoiceCapabilitiesError(error)
        }
      })
    return () => { voiceOwner.current++; controller.abort() }
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
        interviewMode: values.interviewMode,
        providerId: values.providerId,
        knowledgeBaseIds: selectedKbIds,
      })
      if (response.status === 202) navigate(`/interviews/${response.data.sessionId}`)
    } catch (error) { setSubmitError(error) }
    finally { setSubmitting(false) }
  }

  const selectedPreset = presets.find((preset) => preset.id === values.presetId)
  const presetRequirementLines = selectedPreset?.jobDescription
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean) ?? []
  const presetRequirements = (presetRequirementLines.length > 1
    ? presetRequirementLines.slice(1, 4)
    : presetRequirementLines)
  return <section>
    <header className="page-header"><h1>创建 Java 后端面试</h1><p>先异步准备完整题库，准备完成后由你显式开始。面试过程中不进行即时评分。</p></header>
    <form className="interview-form" onSubmit={submit}>
      <label>候选人简历（可选）<select value={values.resumeId} onChange={(event) => setValues({ ...values, resumeId: event.target.value })}><option value="">不使用简历</option>{resumes.map((resume) => <option key={resume.id} value={resume.id}>{resume.originalFilename}</option>)}</select></label>
      <fieldset className="form-group">
        <legend>岗位来源</legend>
        <div className="option-grid">
          <label className={`option-card${values.sourceType === 'PRESET' ? ' is-selected' : ''}`}>
            <input type="radio" name="sourceType" aria-label="预设岗位" checked={values.sourceType === 'PRESET'} onChange={() => setValues({ ...values, sourceType: 'PRESET' })} />
            <span className="option-card-body">
              <span className="option-card-title">预设岗位</span>
              <span className="option-card-desc" aria-hidden="true">使用内置方向题库，开箱即选</span>
            </span>
            <span className="option-card-check" aria-hidden="true" />
          </label>
          <label className={`option-card${values.sourceType === 'CUSTOM' ? ' is-selected' : ''}`}>
            <input type="radio" name="sourceType" aria-label="自定义 JD" checked={values.sourceType === 'CUSTOM'} onChange={() => setValues({ ...values, sourceType: 'CUSTOM' })} />
            <span className="option-card-body">
              <span className="option-card-title">自定义 JD</span>
              <span className="option-card-desc" aria-hidden="true">粘贴岗位描述，按需定制题目</span>
            </span>
            <span className="option-card-check" aria-hidden="true" />
          </label>
        </div>
      </fieldset>
      {values.sourceType === 'PRESET' ? <>
        <label>预设岗位<select value={values.presetId} onChange={(event) => setValues({ ...values, presetId: event.target.value })}>{presets.map((preset) => <option key={preset.id} value={preset.id}>{preset.displayName}</option>)}</select></label>
        {selectedPreset && <section className="preset-job-card" aria-label="岗位概览">
          <div className="preset-job-card-header"><span className="preset-job-card-eyebrow">岗位概览</span><span className="preset-job-card-badge">预设岗位</span></div>
          <h2>{selectedPreset.jobTitle}</h2>
          <p className="preset-job-card-summary">{selectedPreset.description}</p>
          {presetRequirements.length > 0 && <div className="preset-job-card-requirement"><span>岗位需求</span><ul>{presetRequirements.map((requirement) => <li key={requirement}>{requirement}</li>)}</ul></div>}
        </section>}
      </> : <>
        <label>岗位名称<input value={values.jobTitle} maxLength={200} onChange={(event) => setValues({ ...values, jobTitle: event.target.value })} required /></label>
        <label>完整 JD<textarea value={values.jobDescription} maxLength={20_000} rows={10} onChange={(event) => setValues({ ...values, jobDescription: event.target.value })} required /></label>
      </>}
      <fieldset className="form-group">
        <legend>面试模式</legend>
        <div className="option-grid">
          <label className={`option-card${values.interviewMode === 'TEXT' ? ' is-selected' : ''}`}>
            <input type="radio" name="interviewMode" aria-label="文字面试" checked={values.interviewMode === 'TEXT'} onChange={() => setValues({ ...values, interviewMode: 'TEXT' })} />
            <span className="option-card-body">
              <span className="option-card-title">文字面试</span>
              <span className="option-card-desc" aria-hidden="true">键盘输入作答，节奏自主</span>
            </span>
            <span className="option-card-check" aria-hidden="true" />
          </label>
          <label className={`option-card${values.interviewMode === 'VOICE' ? ' is-selected' : ''}${voiceDisabled ? ' is-disabled' : ''}`}>
            <input type="radio" name="interviewMode" aria-label="语音面试" checked={values.interviewMode === 'VOICE'} disabled={voiceDisabled} onChange={() => setValues({ ...values, interviewMode: 'VOICE' })} />
            <span className="option-card-body">
              <span className="option-card-title">语音面试</span>
              <span className="option-card-desc" aria-hidden="true">实时转写，说完手动确认提交</span>
            </span>
            <span className="option-card-check" aria-hidden="true" />
          </label>
        </div>
        {voiceReason && <p className="form-hint" role="status">{voiceReason}</p>}
        <p className="form-hint">语音面试会朗读题目并支持实时对话，也可回退到录音转写后确认；面试模式在创建时确定。</p>
      </fieldset>
      <div className="form-row">
        <label>难度<select value={values.difficulty} onChange={(event) => setValues({ ...values, difficulty: event.target.value as Difficulty })}><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></select></label>
        <label>面试规模<select value={values.interviewSize} onChange={(event) => setValues({ ...values, interviewSize: event.target.value as InterviewSize })}><option value="QUICK">快速面试 · 6 个主问题</option><option value="STANDARD">标准面试 · 9 个主问题</option><option value="DEEP">深度面试 · 12 个主问题</option></select></label>
      </div>
      <label>模型<select value={values.providerId} onChange={(event) => setValues({ ...values, providerId: event.target.value })} required><option value="">请选择</option>{providers.map((provider) => <option key={provider.id} value={provider.id}>{provider.displayName} · {provider.model}</option>)}</select></label>
      {!providers.length && !loadError && <p className="empty-copy">没有可用模型。<Link to="/settings">前往模型设置</Link></p>}
      {knowledgeBases.length > 0 && <fieldset className="kb-select-fieldset"><legend>知识库（可选，最多 5 个）</legend><p className="kb-select-hint">知识库只作为技术参考，RAG 不可用不会阻断题库准备。</p><div className="kb-select-list">{knowledgeBases.map((base) => <label className={`kb-select-item${selectedKbIds.includes(base.knowledgeBaseId) ? ' kb-selected' : ''}`} key={base.knowledgeBaseId}><input type="checkbox" className="visually-hidden" checked={selectedKbIds.includes(base.knowledgeBaseId)} onChange={() => toggleKb(base.knowledgeBaseId)} /><span className="kb-select-name">{base.name}</span><span className="kb-select-count">{base.readyDocumentCount} 个文档</span></label>)}</div></fieldset>}
      <ErrorNotice error={loadError || submitError} />
      <button className="button button-primary" disabled={submitting} type="submit">{submitting ? '正在创建…' : '创建并准备题库'}</button>
    </form>
  </section>
}
