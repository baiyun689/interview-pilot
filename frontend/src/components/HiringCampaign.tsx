import { useEffect, useState, type FormEvent } from 'react'
import { hiringWrite, type Application, type Page } from '../api/hiring'
import { HiringError, HiringPager, useHiringLoad } from './HiringUi'

interface Question { id: string; phase: string; common: boolean; question: string; rubric: { id: string; point: string; acceptance: string }[]; resumeEvidenceIds: string[]; knowledgeEvidenceIds: string[] }
interface Deck { questions: Question[]; knowledgeEvidence?: { status:string; chunks:{pointId:string;filename:string;content:string}[] }[] }
interface Batch { id: number; name: string; roundNo: number; schemeName: string; members: number; ready: number; failed: number; approved: number; issued: number; latestStartAt: string }
interface BatchMember { id: number; candidateName: string; status: string; error: string | null; deck: Deck | null; resumeEvidence: {id:string;text:string}[]; approved: boolean; invitationStatus: string; version: number }
interface Detail { batch: Batch; members: BatchMember[] }
const errorText = (e: unknown) => e instanceof Error ? e.message : '操作失败，请重试'
const stateLabels: Record<string, string> = { PENDING: '排队中', RUNNING: '准备中', COMPLETED: '题目已就绪', FAILED: '准备失败', CANCELLED: '已取消', CREATED: '未下发', ISSUED: '已下发', ACCEPTED: '已确认日程', STARTED: '面试中', DECLINED: '已拒绝' }

export function HiringCampaign({ orgId, jobId }: { orgId: number; jobId: number }) {
  const batches = useHiringLoad<Batch[]>(`/api/organizations/${orgId}/jobs/${jobId}/batches`)
  const [creating, setCreating] = useState(false)
  const [selected, setSelected] = useState<number | null>(null)
  return <section className="hiring-card"><div className="hiring-row"><div><p className="hiring-eyebrow">INTERVIEW CAMPAIGNS</p><h2>面试批次</h2></div>
    <button className="hiring-primary" onClick={() => setCreating(true)}>新建批次</button></div>
    <p>按已发布方案为候选人准备题目，确认后再发出邀请。</p><HiringError message={batches.error} />
    {creating && <BatchCreate orgId={orgId} jobId={jobId} cancel={() => setCreating(false)} done={id => { setCreating(false); setSelected(id); batches.refresh() }} />}
    {batches.loading && <p role="status">正在读取批次…</p>}
    {batches.data?.length === 0 && !creating && <p>还没有面试批次。先发布一份面试方案，再选择候选人。</p>}
    {batches.data?.map(b => <div className="hiring-list-row" key={b.id}><div className="hiring-row"><div><strong>{b.name}</strong><p>第 {b.roundNo} 轮 · {b.schemeName} · {b.members} 位候选人</p></div>
      <button aria-expanded={selected === b.id} onClick={() => setSelected(selected === b.id ? null : b.id)}>查看准备进度</button></div>
      {selected === b.id && <BatchDetail key={b.id} orgId={orgId} batchId={b.id} changed={batches.refresh} />}</div>)}
  </section>
}

function BatchCreate({ orgId, jobId, cancel, done }: { orgId: number; jobId: number; cancel: () => void; done: (id: number) => void }) {
  const schemes = useHiringLoad<{ id: number; name: string; publishedRevision: number }[]>(`/api/organizations/${orgId}/jobs/${jobId}/schemes`)
  const [scheme, setScheme] = useState('')
  const revisions = useHiringLoad<{ id: number; revision: number; retired: boolean; definition: { durationMinutes: number } }[]>(scheme ? `/api/organizations/${orgId}/schemes/${scheme}/revisions` : null)
  const [revision, setRevision] = useState('')
  const [page, setPage] = useState(0)
  const applications = useHiringLoad<Page<Application>>(`/api/organizations/${orgId}/jobs/${jobId}/applications?page=${page}`)
  const [selected, setSelected] = useState<number[]>([])
  const [name, setName] = useState('第一轮技术面试')
  const [round, setRound] = useState(1)
  const [opens, setOpens] = useState('')
  const [latest, setLatest] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // The same content retains its key across an uncertain network response.
  const [request, setRequest] = useState<{ content: string; key: string } | null>(null)
  async function submit(e: FormEvent) {
    e.preventDefault(); if (busy) return
    const chosen = revisions.data?.find(r => String(r.id) === revision)
    if (!chosen || !selected.length) { setError('请选择方案版本与候选人'); return }
    const payload = { name, roundNo: round, schemeRevisionId: chosen.id, applicationIds: [...selected].sort((a,b) => a-b), opensAt: new Date(opens).toISOString(), latestStartAt: new Date(latest).toISOString(), closesAt: new Date(new Date(latest).getTime()+chosen.definition.durationMinutes*60000).toISOString(), timezone: Intl.DateTimeFormat().resolvedOptions().timeZone }
    const content = JSON.stringify(payload)
    const key = request?.content === content ? request.key : crypto.randomUUID()
    setRequest({content,key}); setBusy(true); setError('')
    try { const result = await hiringWrite<Detail>(`/api/organizations/${orgId}/jobs/${jobId}/batches`, {...payload,requestKey:key}); done(result.batch.id) }
    catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }
  return <form className="hiring-stage-editor" onSubmit={e => void submit(e)}><h3>安排一批面试</h3><HiringError message={error || schemes.error || revisions.error || applications.error} />
    <fieldset disabled={busy} className="hiring-fieldset"><label>批次名称<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label>
      <div className="hiring-grid"><label>面试方案<select required value={scheme} onChange={e => { setScheme(e.target.value); setRevision('') }}><option value="">选择方案</option>{schemes.data?.filter(s => s.publishedRevision > 0).map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        <label>发布版本<select required value={revision} onChange={e => setRevision(e.target.value)}><option value="">选择版本</option>{revisions.data?.filter(r => !r.retired).map(r => <option key={r.id} value={r.id}>版本 {r.revision} · {r.definition.durationMinutes} 分钟</option>)}</select></label></div>
      <label>招聘轮次<input type="number" required min={1} max={20} value={round} onChange={e => setRound(Number(e.target.value))} /></label>
      <div className="hiring-grid"><label>开放时间<input type="datetime-local" required value={opens} onChange={e => setOpens(e.target.value)} /></label><label>最晚开始时间<input type="datetime-local" required min={opens} value={latest} onChange={e => setLatest(e.target.value)} /></label></div>
      <p className="hiring-footnote">时区：{Intl.DateTimeFormat().resolvedOptions().timeZone}。硬截止时间自动加上方案时长，确保最晚开始仍可完成面试。</p>
      <h4>选择候选人 · 已选 {selected.length} 人</h4>
      {applications.data?.items.filter(a => !['WITHDRAWN','FINISHED'].includes(a.status)).map(a => <label className="hiring-checkbox" key={a.id}><input type="checkbox" checked={selected.includes(a.id)} onChange={e => setSelected(e.target.checked ? [...selected,a.id] : selected.filter(id => id !== a.id))} />{a.candidateName}</label>)}
      <HiringPager page={page} hasMore={!!applications.data?.hasMore} change={setPage} />
      <div className="hiring-actions"><button className="hiring-primary" disabled={!selected.length}>{busy ? '正在创建…' : '创建并准备题目'}</button><button type="button" onClick={cancel}>取消</button></div>
    </fieldset></form>
}

function BatchDetail({ orgId, batchId, changed }: { orgId: number; batchId: number; changed: () => void }) {
  const detail = useHiringLoad<Detail>(`/api/organizations/${orgId}/batches/${batchId}`)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState('')
  const [preview, setPreview] = useState<number | null>(null)
  const [terminating, setTerminating] = useState<number | null>(null)
  const working = detail.data?.members.some(m => ['PENDING','RUNNING'].includes(m.status))
  const inProgress = working || detail.data?.members.some(m => m.invitationStatus === 'STARTED')
  useEffect(() => { if (!inProgress) return; const timer = window.setInterval(detail.refresh,4000); return () => window.clearInterval(timer) },[inProgress,orgId,batchId])
  async function action(path: string) {
    setBusy(true); setError(''); setResult('')
    try { const res = await hiringWrite<{ issued: number[]; skipped: number[] } | undefined>(path)
      if (res?.issued) setResult(`已下发 ${res.issued.length} 人，未下发 ${res.skipped.length} 人。未完成准备或确认的成员会保留在当前批次。`)
      detail.refresh(); changed()
    } catch (e) { setError(errorText(e)) } finally { setBusy(false) }
  }
  return <div className="hiring-preview"><HiringError message={error || detail.error} />{result && <p role="status" className="hiring-progress">{result}</p>}
    {detail.data && <><div className="hiring-stages"><div><small>题目就绪</small><strong>{detail.data.batch.ready} / {detail.data.batch.members}</strong></div><div><small>企业已确认</small><strong>{detail.data.batch.approved}</strong></div><div><small>准备失败 / 取消</small><strong>{detail.data.batch.failed}</strong></div></div>
      <div className="hiring-actions"><button className="hiring-primary" disabled={busy || detail.data.batch.approved === 0} onClick={() => void action(`/api/organizations/${orgId}/batches/${batchId}/publish`)}>下发已确认成员</button>{working && <span role="status"><span className="hiring-pulse" />正在逐人准备</span>}</div>
      {detail.data.members.map(m => <div className="hiring-list-row" key={m.id}><div className="hiring-row"><div><strong>{m.candidateName}</strong><p>{stateLabels[m.status] ?? m.status} · {m.approved ? '已确认' : '待确认'} · {stateLabels[m.invitationStatus] ?? m.invitationStatus}</p></div><div className="hiring-actions">
        {m.deck && <button aria-expanded={preview === m.id} onClick={() => setPreview(preview === m.id ? null : m.id)}>预览题目</button>}
        {m.status === 'FAILED' && m.invitationStatus === 'CREATED' && <button disabled={busy} onClick={() => void action(`/api/organizations/${orgId}/batches/${batchId}/members/${m.id}/retry`)}>重试准备</button>}
        {['CREATED','ISSUED','ACCEPTED'].includes(m.invitationStatus) && <button disabled={busy} onClick={() => void action(`/api/organizations/${orgId}/batch-members/${m.id}/cancel-invitation`)}>撤销邀请</button>}
        {m.invitationStatus === 'STARTED' && <button disabled={busy} onClick={() => setTerminating(m.id)}>终止面试</button>}
      </div></div><HiringError message={m.error ?? ''} />
        {terminating === m.id && m.invitationStatus === 'STARTED' && <div className="hiring-stage-editor"><p>确认终止 {m.candidateName} 的本次面试？候选人将无法继续作答。</p><button disabled={busy} onClick={() => void action(`/api/organizations/${orgId}/batch-members/${m.id}/cancel-invitation`).then(()=>setTerminating(null))}>确认终止</button><button disabled={busy} onClick={()=>setTerminating(null)}>保留面试</button></div>}
        {preview === m.id && m.deck && <DeckPreview key={`${m.id}-${m.version}`} member={m} orgId={orgId} batchId={batchId} done={() => { detail.refresh(); changed() }} />}
      </div>)}</>}
  </div>
}

function DeckPreview({member,orgId,batchId,done}:{member:BatchMember;orgId:number;batchId:number;done:()=>void}) {
  const [deck,setDeck] = useState<Deck>(member.deck!)
  const [busy,setBusy] = useState(false)
  const [error,setError] = useState('')
  const editable = member.invitationStatus === 'CREATED'
  async function approve(e:FormEvent) {
    e.preventDefault(); setBusy(true);setError('')
    try { await hiringWrite(`/api/organizations/${orgId}/batches/${batchId}/members/${member.id}/approval`,{version:member.version,deck},'PUT');done() }
    catch(e){setError(errorText(e))}finally{setBusy(false)}
  }
  return <form className="hiring-stage-editor" onSubmit={e=>void approve(e)}><HiringError message={error}/><p>公共题保持方案原文。修改定制题时请同时核对评分标准，保存后才可下发。</p>
    {deck.questions.map((q,i)=><article className="hiring-evidence" key={q.id}><span className="hiring-badge">第 {i+1} 题 · {q.common?'公共题':'定制题'}</span>
      <label>题目<textarea required maxLength={3000} value={q.question} disabled={busy || !editable || q.common} onChange={e=>setDeck({questions:deck.questions.map((v,n)=>n===i?{...v,question:e.target.value}:v)})}/></label>
      {!q.common && <details><summary>简历依据 · 已选 {q.resumeEvidenceIds.length} 段</summary>{member.resumeEvidence.map(fragment=><label className="hiring-checkbox" key={fragment.id}><input type="checkbox" disabled={busy || !editable} checked={q.resumeEvidenceIds.includes(fragment.id)} onChange={e=>setDeck({questions:deck.questions.map((v,n)=>n===i?{...v,resumeEvidenceIds:e.target.checked?[...v.resumeEvidenceIds,fragment.id]:v.resumeEvidenceIds.filter(id=>id!==fragment.id)}:v)})}/><span>{fragment.text}</span></label>)}</details>}
      <details><summary>企业知识依据 · {q.knowledgeEvidenceIds.length ? `${q.knowledgeEvidenceIds.length} 段引用` : '通用知识模式'}</summary>
        {(member.deck?.knowledgeEvidence ?? []).flatMap(e=>e.chunks).filter((chunk,index,all)=>all.findIndex(c=>c.pointId===chunk.pointId)===index).map(chunk=><label className="hiring-checkbox" key={chunk.pointId}><input type="checkbox" disabled={busy || !editable} checked={q.knowledgeEvidenceIds.includes(chunk.pointId)} onChange={e=>setDeck({questions:deck.questions.map((v,n)=>n===i?{...v,knowledgeEvidenceIds:e.target.checked?[...v.knowledgeEvidenceIds,chunk.pointId]:v.knowledgeEvidenceIds.filter(id=>id!==chunk.pointId)}:v)})}/><span><strong>{chunk.filename}</strong><br/>{chunk.content}</span></label>)}
        {!member.deck?.knowledgeEvidence?.some(e=>e.chunks.length) && <p>没有可引用的企业资料，本题使用通用知识。</p>}
      </details>
      {q.rubric.map((r,ri)=><label key={r.id}>{r.point}<textarea required maxLength={1000} value={r.acceptance} disabled={busy || !editable || q.common} onChange={e=>setDeck({questions:deck.questions.map((v,n)=>n===i?{...v,rubric:v.rubric.map((p,k)=>k===ri?{...p,acceptance:e.target.value}:p)}:v)})}/></label>)}
    </article>)}
    {editable && <button className="hiring-primary" disabled={busy}>{busy?'正在保存…':'确认题目与评分标准'}</button>}
  </form>
}
