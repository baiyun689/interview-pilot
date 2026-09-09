import { useEffect, useState, type FormEvent } from 'react'
import { hiringWrite } from '../api/hiring'
import type { AiProvider } from '../types/provider'
import { HiringError, useHiringLoad } from './HiringUi'

type Phase = 'SELF_INTRODUCTION' | 'FUNDAMENTALS' | 'PROJECT_EXPERIENCE' | 'SCENARIO_TRADEOFF'
const phases: Record<Phase, string> = { SELF_INTRODUCTION: '自我介绍', FUNDAMENTALS: '基础知识', PROJECT_EXPERIENCE: '项目经历', SCENARIO_TRADEOFF: '场景分析' }
interface Stage { phase: Phase; questionCount: number; followUpLimit: number }
interface Rubric { id: string; point: string; acceptance: string }
interface CommonQuestion { id: string; phase: Phase; question: string; rubric: Rubric[] }
interface Definition { difficulty: 'EASY' | 'MEDIUM' | 'HARD'; mode: 'TEXT' | 'VOICE'; durationMinutes: number; stages: Stage[]; commonQuestions: CommonQuestion[]; providerId: string; knowledgeBaseIds: string[] }
interface Scheme { id: number; jobId: number; name: string; definition: Definition; publishedRevision: number; version: number }
interface Analysis {
  id: number; status: string; error: string | null; attempts: number
  input: { providerId: string; modelName: string; promptVersion: string; requirements: { id: string; text: string }[]; resumeEvidence: { id: string; text: string }[] }
  result: { findings: { requirementId: string; status: string; evidenceIds: string[]; explanation: string; suggestedQuestions: string[] }[] } | null
}
const errorMessage = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试'

export function HiringApplicationAnalysis({ orgId, applicationId, status }: { orgId: number; applicationId: number; status: string }) {
  const analysis = useHiringLoad<Analysis | null>(`/api/organizations/${orgId}/applications/${applicationId}/analysis`)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const working = analysis.data?.status === 'PENDING' || analysis.data?.status === 'RUNNING'
  // Poll only while an observed task is active; cleanup stops polling when changing candidates.
  useEffect(() => {
    if (!working) return
    const id = window.setInterval(analysis.refresh, 4000)
    return () => window.clearInterval(id)
  }, [working, applicationId, orgId])
  async function start(retry: boolean) {
    setBusy(true); setError('')
    try {
      await hiringWrite(retry ? `/api/organizations/${orgId}/analysis-tasks/${analysis.data?.id}/retry` : `/api/organizations/${orgId}/applications/${applicationId}/analysis`, {})
      analysis.refresh()
    } catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <section className="hiring-card"><div className="hiring-row"><div><p className="hiring-eyebrow">EVIDENCE / INTERVIEW PREPARATION</p><h3>岗位证据分析</h3></div>
    {!analysis.data && !analysis.loading && !analysis.error && !['WITHDRAWN', 'FINISHED'].includes(status) && <button className="hiring-primary" disabled={busy} onClick={() => void start(false)}>生成分析</button>}</div>
    <p>对照提交时的岗位要求，整理简历证据与面试中需要核实的问题。</p><HiringError message={error || analysis.error} />
    {working && <div className="hiring-progress" role="status"><span className="hiring-pulse" />{analysis.data?.status === 'RUNNING' ? '正在逐项核对材料…' : '已加入分析队列，请稍候…'}{analysis.data?.error && <p>{analysis.data.error}</p>}</div>}
    {analysis.data?.status === 'FAILED' && <div><HiringError message={analysis.data.error ?? '分析失败'} /><button disabled={busy} onClick={() => void start(true)}>重试分析</button></div>}
    {analysis.data?.status === 'CANCELLED' && <p>{analysis.data.error}</p>}
    {analysis.data?.result?.findings.map(finding => <article className="hiring-evidence" key={finding.requirementId}>
      <span className={`hiring-badge ${finding.status === 'SUPPORTED' ? 'hiring-badge-green' : ''}`}>{finding.status === 'SUPPORTED' ? '简历有相关描述' : '需要面试核实'}</span>
      <h4>{analysis.data?.input.requirements.find(r => r.id === finding.requirementId)?.text}</h4><p>{finding.explanation}</p>
      {finding.evidenceIds.map(id => <blockquote key={id}>{analysis.data?.input.resumeEvidence.find(r => r.id === id)?.text}</blockquote>)}
      {finding.suggestedQuestions.length > 0 && <><h5>建议追问</h5><ul>{finding.suggestedQuestions.map((q, i) => <li key={i}>{q}</li>)}</ul></>}
    </article>)}
    {analysis.data?.result && <p className="hiring-footnote">分析依据当前投递材料生成，简历描述仍需面试核实。模型：{analysis.data.input.modelName}</p>}
  </section>
}

export function HiringSchemes({ orgId, jobId }: { orgId: number; jobId: number }) {
  const schemes = useHiringLoad<Scheme[]>(`/api/organizations/${orgId}/jobs/${jobId}/schemes`)
  const [editing, setEditing] = useState<Scheme | 'new' | null>(null)
  const [preview, setPreview] = useState<Scheme | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function publish(scheme: Scheme) {
    setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${orgId}/schemes/${scheme.id}/publish`, { version: scheme.version }); schemes.refresh(); setPreview(null) }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <section className="hiring-card"><div className="hiring-row"><h2>面试方案</h2><button onClick={() => setEditing('new')}>创建方案</button></div>
    <HiringError message={error || schemes.error} />{schemes.loading && <p>正在读取方案…</p>}
    {schemes.data?.length === 0 && <p>先定义考察阶段与公共题，再为候选人生成项目定制题。</p>}
    {schemes.data?.map(scheme => <div className="hiring-list-row" key={scheme.id}><div className="hiring-row"><div><strong>{scheme.name}</strong><p>{scheme.definition.stages.reduce((sum, s) => sum + s.questionCount, 0)} 道主问题 · {scheme.definition.durationMinutes} 分钟 · 已发布版本 {scheme.publishedRevision}</p></div><div className="hiring-actions">
      <button disabled={busy} onClick={() => setEditing(scheme)}>编辑</button><button disabled={busy} onClick={() => setPreview(scheme)}>预览并发布</button></div></div></div>)}
    {schemes.data?.map(scheme => <SchemeHistory key={scheme.id} orgId={orgId} scheme={scheme} />)}
    {editing && <SchemeEditor key={editing === 'new' ? 'new' : editing.id} orgId={orgId} jobId={jobId} scheme={editing === 'new' ? null : editing} close={() => setEditing(null)} saved={() => { setEditing(null); schemes.refresh() }} />}
    {preview && <div className="hiring-preview"><h3>发布前确认：{preview.name}</h3><p>发布后会保留不可变版本，后续编辑不会修改已下发的面试。</p>
      <div className="hiring-stages">{preview.definition.stages.map((stage, i) => <div key={stage.phase}><span>{i + 1}</span><strong>{phases[stage.phase]}</strong><small>{stage.questionCount} 题 · 每题最多追问 {stage.followUpLimit} 次</small></div>)}</div>
      {preview.definition.commonQuestions.map((q, i) => <article key={q.id} className="hiring-evidence"><h4>公共题 {i + 1} · {phases[q.phase]}</h4><p>{q.question}</p>{q.rubric.map(r => <p key={r.id}><strong>{r.point}</strong>：{r.acceptance}</p>)}</article>)}
      <div className="hiring-actions"><button className="hiring-primary" disabled={busy} onClick={() => void publish(preview)}>确认发布此版本</button><button disabled={busy} onClick={() => setPreview(null)}>返回修改</button></div>
    </div>}
  </section>
}

function SchemeEditor({ orgId, jobId, scheme, close, saved }: { orgId: number; jobId: number; scheme: Scheme | null; close: () => void; saved: () => void }) {
  const providers = useHiringLoad<AiProvider[]>('/api/ai/providers')
  const knowledge = useHiringLoad<{ id: string; name: string }[]>(`/api/organizations/${orgId}/knowledge`)
  const [name, setName] = useState(scheme?.name ?? 'Java 后端一面')
  const [definition, setDefinition] = useState<Definition>(scheme?.definition ?? {
    difficulty: 'MEDIUM', mode: 'TEXT', durationMinutes: 30, providerId: '', commonQuestions: [], knowledgeBaseIds: [],
    stages: [{ phase: 'SELF_INTRODUCTION', questionCount: 1, followUpLimit: 0 }, { phase: 'FUNDAMENTALS', questionCount: 2, followUpLimit: 1 }, { phase: 'PROJECT_EXPERIENCE', questionCount: 2, followUpLimit: 1 }, { phase: 'SCENARIO_TRADEOFF', questionCount: 1, followUpLimit: 1 }],
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const providerId = definition.providerId || providers.data?.find(p => p.enabled && p.defaultProvider)?.id || providers.data?.find(p => p.enabled)?.id || ''
  const total = definition.stages.reduce((n, s) => n + s.questionCount, 0)
  function changeStage(index: number, patch: Partial<Stage>) { setDefinition(d => ({ ...d, stages: d.stages.map((s, i) => i === index ? { ...s, ...patch } : s) })) }
  function reorder(index: number, delta: number) {
    setDefinition(d => { const stages = [...d.stages]; [stages[index], stages[index + delta]] = [stages[index + delta], stages[index]]; return { ...d, stages } })
  }
  function changeQuestion(id: string, patch: Partial<CommonQuestion>) { setDefinition(d => ({ ...d, commonQuestions: d.commonQuestions.map(q => q.id === id ? { ...q, ...patch } : q) })) }
  function addQuestion() {
    if (!definition.stages.length) return
    setDefinition(d => ({ ...d, commonQuestions: [...d.commonQuestions, { id: crypto.randomUUID(), phase: d.stages[0].phase, question: '', rubric: [{ id: crypto.randomUUID(), point: '', acceptance: '' }] }] }))
  }
  async function save(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${orgId}/jobs/${jobId}/schemes${scheme ? `/${scheme.id}` : ''}`, { name, definition: { ...definition, providerId }, version: scheme?.version ?? 0 }, scheme ? 'PUT' : 'POST'); saved() }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <form className="hiring-editor" onSubmit={e => void save(e)}><h3>{scheme ? '编辑方案草稿' : '新建面试方案'}</h3><HiringError message={error || providers.error} />
    <label>方案名称<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label>
    <div className="hiring-grid"><label>难度<select value={definition.difficulty} onChange={e => setDefinition({ ...definition, difficulty: e.target.value as Definition['difficulty'] })}><option value="EASY">基础</option><option value="MEDIUM">标准</option><option value="HARD">深入</option></select></label>
      <label>作答方式<select value={definition.mode} onChange={e => setDefinition({ ...definition, mode: e.target.value as Definition['mode'] })}><option value="TEXT">文本</option><option value="VOICE">语音</option></select></label>
      <label>总时长（分钟）<input type="number" min={5} max={90} required value={definition.durationMinutes} onChange={e => setDefinition({ ...definition, durationMinutes: Number(e.target.value) })} /></label>
      <label>评估模型<select required value={providerId} onChange={e => setDefinition({ ...definition, providerId: e.target.value })}><option value="">请选择已配置模型</option>{providers.data?.filter(p => p.enabled).map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}</select></label></div>
    <details><summary>关联企业知识库（可选）</summary><p>发布时固定可用资料版本，用于出题和评估。停用方案版本后释放对应资料引用。</p><HiringError message={knowledge.error} />
      {knowledge.data?.map(base => <label className="hiring-checkbox" key={base.id}><input type="checkbox" checked={definition.knowledgeBaseIds.includes(base.id)} disabled={!definition.knowledgeBaseIds.includes(base.id) && definition.knowledgeBaseIds.length >= 5}
        onChange={e => setDefinition({ ...definition, knowledgeBaseIds: e.target.checked ? [...definition.knowledgeBaseIds, base.id] : definition.knowledgeBaseIds.filter(id => id !== base.id) })} />{base.name}</label>)}</details>
    <h4>面试阶段</h4><p>按顺序执行，共 {total} 道主问题。未配置为公共题的名额用于生成简历定制题。</p>
    {definition.stages.map((stage, index) => <div className="hiring-stage-editor" key={stage.phase}><strong>{index + 1}. {phases[stage.phase]}</strong><div className="hiring-grid">
      <label>题数<input aria-label={`${phases[stage.phase]}题数`} type="number" min={1} max={stage.phase === 'SELF_INTRODUCTION' ? 1 : 8} value={stage.questionCount} onChange={e => changeStage(index, { questionCount: Number(e.target.value) })} /></label>
      <label>追问上限<input aria-label={`${phases[stage.phase]}追问上限`} type="number" min={0} max={stage.phase === 'SELF_INTRODUCTION' ? 0 : 2} value={stage.followUpLimit} onChange={e => changeStage(index, { followUpLimit: Number(e.target.value) })} /></label></div>
      <div className="hiring-actions"><button type="button" disabled={index === 0} onClick={() => reorder(index, -1)}>上移</button><button type="button" disabled={index === definition.stages.length - 1} onClick={() => reorder(index, 1)}>下移</button><button type="button" disabled={definition.commonQuestions.some(q => q.phase === stage.phase)} onClick={() => setDefinition({ ...definition, stages: definition.stages.filter(s => s.phase !== stage.phase) })}>移除阶段</button></div></div>)}
    <div className="hiring-actions">{(Object.entries(phases) as [Phase, string][]).filter(([phase]) => !definition.stages.some(s => s.phase === phase)).map(([phase, label]) => <button type="button" key={phase} onClick={() => setDefinition({ ...definition, stages: [...definition.stages, { phase, questionCount: 1, followUpLimit: 0 }] })}>添加{label}</button>)}</div>
    <h4>公共题与评分标准</h4><p>同一批次保留一致的公共题，便于按相同考察点复核回答。</p>
    {definition.commonQuestions.map((q, i) => <div className="hiring-stage-editor" key={q.id}><div className="hiring-row"><strong>公共题 {i + 1}</strong><button type="button" onClick={() => setDefinition({ ...definition, commonQuestions: definition.commonQuestions.filter(other => other.id !== q.id) })}>移除题目</button></div>
      <label>所属阶段<select value={q.phase} onChange={e => changeQuestion(q.id, { phase: e.target.value as Phase })}>{definition.stages.map(s => <option key={s.phase} value={s.phase}>{phases[s.phase]}</option>)}</select></label>
      <label>题目<textarea required rows={3} maxLength={3000} value={q.question} onChange={e => changeQuestion(q.id, { question: e.target.value })} /></label>
      {q.rubric.map((r, ri) => <div key={r.id} className="hiring-grid"><label>考察点 {ri + 1}<input required maxLength={300} value={r.point} onChange={e => changeQuestion(q.id, { rubric: q.rubric.map(item => item.id === r.id ? { ...item, point: e.target.value } : item) })} /></label>
        <label>认可的回答要点<textarea required rows={2} maxLength={1000} value={r.acceptance} onChange={e => changeQuestion(q.id, { rubric: q.rubric.map(item => item.id === r.id ? { ...item, acceptance: e.target.value } : item) })} /></label></div>)}
      {q.rubric.length < 8 && <button type="button" onClick={() => changeQuestion(q.id, { rubric: [...q.rubric, { id: crypto.randomUUID(), point: '', acceptance: '' }] })}>添加考察点</button>}
    </div>)}
    <button type="button" disabled={definition.commonQuestions.length >= total || !definition.stages.length} onClick={addQuestion}>添加公共题</button>
    <div className="hiring-actions"><button className="hiring-primary" disabled={busy || !providerId}>保存方案草稿</button><button type="button" disabled={busy} onClick={close}>取消</button></div>
  </form>
}

function SchemeHistory({ orgId, scheme }: { orgId: number; scheme: Scheme }) {
  const [open, setOpen] = useState(false)
  const revisions = useHiringLoad<{ id: number; revision: number; name: string; publishedAt: string; retired: boolean }[]>(open ? `/api/organizations/${orgId}/schemes/${scheme.id}/revisions` : null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function retire(revision: number) {
    setBusy(true); setError('')
    try { await hiringWrite(`/api/organizations/${orgId}/schemes/${scheme.id}/revisions/${revision}/retire`); revisions.refresh() }
    catch (e) { setError(errorMessage(e)) } finally { setBusy(false) }
  }
  return <details onToggle={e => setOpen(e.currentTarget.open)}><summary>{scheme.name} · 发布历史</summary><HiringError message={error || revisions.error} />
    {revisions.data?.map(r => <div className="hiring-row hiring-list-row" key={r.id}><p>版本 {r.revision} · {new Date(r.publishedAt).toLocaleString()} · {r.retired ? '已停用' : '可用于新批次'}</p>
      {!r.retired && <button disabled={busy} onClick={() => void retire(r.revision)}>停用此版本</button>}</div>)}</details>
}
