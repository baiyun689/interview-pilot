import { useState } from 'react'
import { hiringWrite, type Page } from '../api/hiring'
import { HiringError, HiringPager, useHiringLoad } from './HiringUi'

export const decisionLabels: Record<string,string> = {NEXT_ROUND:'进入下一轮',MORE_ASSESSMENT:'待补充评估',END_PROCESS:'结束流程'}
interface Summary {invitationId:string;candidateName:string;jobTitle:string;roundNo:number;status:string}
interface Content {dimensions:{name:string;evaluation:string}[];evidenceTurns:number[];notes:string;decision:string}
interface Review {id:number;reviewerId:number;reviewerName:string;status:string;version:number;content:Content}
interface Revision {id:number;reviewerId:number;revision:number;content:Content;submittedAt:string}
export interface Feedback {revision:number;decision:string;feedback:string;publishedAt:string}
interface Evaluation {score:number;coveredPoints:string[];missingPoints:{keyPoint:string;why:string}[];factualIssues:string[];citedSourceIds:string[];model:string}
interface EvidenceTurn {turnNo:number;question:string;answer:string|null;evaluationStatus:string;evaluation?:Evaluation;rubric?:{keyPoint:string;acceptanceHint:string}[];knowledge?:{chunks:{pointId:string;filename:string;content:string;section:string;pageNumber:number|null}[]}}
interface Detail {interview:Summary;sessionStatus:string;aiReport:{summary?:string;overallScore?:number;strengths?:string[];improvements?:string[];phaseScores?:Record<string,number>;technicalReferences?:{sourceId:string;note:string}[]}|null;turns:EvidenceTurn[];reviews:Review[];history:Revision[];assignments:number[];publications:Feedback[];currentUserId:number;canPublish:boolean}
const errorText=(e:unknown)=>e instanceof Error?e.message:'操作失败，请重试'

export function HiringReviews({orgId}:{orgId:number}) {
  const [page,setPage]=useState(0),[selected,setSelected]=useState<string|null>(null)
  const list=useHiringLoad<Page<Summary>>(`/api/organizations/${orgId}/reviews?page=${page}`)
  return <section className="hiring-review-workspace"><div className="hiring-row"><div><h2>面试与评审</h2><p>结合回答证据提交人工意见，再由招聘负责人发布反馈。</p></div><button onClick={list.refresh}>刷新列表</button></div>
    <HiringError message={list.error}/>{list.loading&&<p role="status">正在读取面试…</p>}
    {list.data?.items.length===0&&<div className="hiring-card">暂无可评审的面试。候选人开始面试后会出现在这里。</div>}
    {list.data?.items.map(item=><article className="hiring-card" key={item.invitationId}><div className="hiring-row"><div><h3>{item.candidateName}</h3><p>{item.jobTitle} · 第 {item.roundNo} 轮</p></div><button className="hiring-primary" onClick={()=>setSelected(selected===item.invitationId?null:item.invitationId)}>{selected===item.invitationId?'收起评审':'查看面试与评审'}</button></div>
      {selected===item.invitationId&&<ReviewDetail orgId={orgId} id={item.invitationId}/>}</article>)}
    <HiringPager page={page} hasMore={!!list.data?.hasMore} change={n=>{setPage(n);setSelected(null)}}/>
  </section>
}
function ReviewDetail({orgId,id}:{orgId:number;id:string}) {
  const detail=useHiringLoad<Detail>(`/api/organizations/${orgId}/reviews/${id}`)
  return <><HiringError message={detail.error}/>{detail.loading&&<p role="status">正在读取评审材料…</p>}{detail.data&&<ReviewEditor key={JSON.stringify([detail.data.reviews,detail.data.publications,detail.data.assignments])} orgId={orgId} id={id} data={detail.data} changed={detail.refresh}/>}</>
}
function ReviewEditor({orgId,id,data,changed}:{orgId:number;id:string;data:Detail;changed:()=>void}) {
  const mine=data.reviews.find(r=>r.reviewerId===data.currentUserId)
  const [content,setContent]=useState<Content>(mine?.content??{dimensions:[{name:'技术能力',evaluation:''},{name:'项目理解',evaluation:''}],notes:'',evidenceTurns:[],decision:'MORE_ASSESSMENT'})
  const [feedback,setFeedback]=useState(''),[revisionId,setRevisionId]=useState(''),[reviewer,setReviewer]=useState('')
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[success,setSuccess]=useState(''),[confirm,setConfirm]=useState(false)
  const base=`/api/organizations/${orgId}/reviews/${id}`
  const finished=!['PREPARING','READY','INTERVIEWING'].includes(data.sessionStatus)
  const reviewers=useHiringLoad<{userId:number;displayName:string}[]>(data.canPublish?`/api/organizations/${orgId}/reviewers`:null)
  async function action(path:string,body:unknown,method='POST') {if(busy)return;setBusy(true);setError('');try{await hiringWrite(path,body,method);setSuccess('已保存');changed()}catch(e){setError(errorText(e))}finally{setBusy(false)}}
  const selectedRevision=data.history.find(r=>r.id===Number(revisionId))
  return <div className="hiring-review-detail"><HiringError message={error}/>{success&&<p role="status">{success}</p>}
    <section className="hiring-stage-editor"><h3>AI 评估参考</h3>{data.aiReport?<><p>{data.aiReport.summary}</p>{data.aiReport.overallScore!=null&&<p>参考分数：{data.aiReport.overallScore}</p>}</>:<p>AI 报告尚未就绪或生成失败。可以根据已保存回答完成人工评审，缺失评估不视为零分。</p>}</section>
    {data.aiReport&&<div className="hiring-grid"><section><h4>AI 观察到的优势</h4>{data.aiReport.strengths?.map((v,i)=><p key={i}>{v}</p>)}</section><section><h4>待核实与改进</h4>{data.aiReport.improvements?.map((v,i)=><p key={i}>{v}</p>)}</section></div>}
    <h3>回答证据</h3><div className="hiring-review-evidence">{data.turns.map(t=><details key={t.turnNo}><summary>第 {t.turnNo} 题 · {t.question}</summary><p className="hiring-review-text">{t.answer??'本题未作答'}</p><TurnEvaluation turn={t}/></details>)}</div>
    {data.canPublish&&<details className="hiring-stage-editor"><summary>指派评审人员</summary><HiringError message={reviewers.error}/><div className="hiring-actions"><select aria-label="评审人员" value={reviewer} onChange={e=>setReviewer(e.target.value)}><option value="">选择企业成员</option>{reviewers.data?.map(m=><option key={m.userId} value={m.userId}>{m.displayName}</option>)}</select><button disabled={busy||!reviewer} onClick={()=>void action(`${base}/assignments/${reviewer}`,{},'PUT')}>添加指派</button></div>{data.assignments.map(uid=><div className="hiring-row" key={uid}><span>{reviewers.data?.find(m=>m.userId===uid)?.displayName??`成员 ${uid}`}</span><button disabled={busy} onClick={()=>void action(`${base}/assignments/${uid}`,{},'DELETE')}>移除指派</button></div>)}</details>}
    <form onSubmit={e=>{e.preventDefault();void action(base,{version:mine?.version??-1,content,submit:true},'PUT')}} className="hiring-stage-editor"><h3>我的人工评审</h3><p>仅企业授权人员可见。提交后再次编辑会生成新修订，原记录保留。</p>
      {content.dimensions.map((d,i)=><div className="hiring-grid" key={i}><label>评价维度<input required maxLength={100} value={d.name} onChange={e=>setContent({...content,dimensions:content.dimensions.map((v,n)=>n===i?{...v,name:e.target.value}:v)})}/></label><label>维度评价<textarea required maxLength={2000} value={d.evaluation} onChange={e=>setContent({...content,dimensions:content.dimensions.map((v,n)=>n===i?{...v,evaluation:e.target.value}:v)})}/></label></div>)}
      <button type="button" disabled={content.dimensions.length>=20} onClick={()=>setContent({...content,dimensions:[...content.dimensions,{name:'',evaluation:''}]})}>添加评价维度</button>
      <fieldset><legend>引用回答证据</legend>{data.turns.filter(t=>t.answer).map(t=><label className="hiring-checkbox" key={t.turnNo}><input type="checkbox" checked={content.evidenceTurns.includes(t.turnNo)} onChange={e=>setContent({...content,evidenceTurns:e.target.checked?[...content.evidenceTurns,t.turnNo]:content.evidenceTurns.filter(n=>n!==t.turnNo)})}/>第 {t.turnNo} 题</label>)}</fieldset>
      <label>内部评语<textarea required maxLength={10000} rows={4} value={content.notes} onChange={e=>setContent({...content,notes:e.target.value})}/></label>
      <label>下一步决定<select value={content.decision} onChange={e=>setContent({...content,decision:e.target.value})}>{Object.entries(decisionLabels).map(([k,v])=><option key={k} value={k}>{v}</option>)}</select></label>
      {!finished&&<p>面试尚未结束，暂不能保存评审。</p>}<div className="hiring-actions"><button type="button" disabled={busy||!finished} onClick={()=>void action(base,{version:mine?.version??-1,content,submit:false},'PUT')}>保存草稿</button><button className="hiring-primary" disabled={busy||!finished}>提交人工评审</button></div></form>
    <details className="hiring-stage-editor"><summary>已提交评审与历史修订（{data.history.length}）</summary>{data.history.map(r=><article key={r.id}><h4>{data.reviews.find(v=>v.reviewerId===r.reviewerId)?.reviewerName} · 修订 {r.revision}</h4><p>{decisionLabels[r.content.decision]} · {new Date(r.submittedAt).toLocaleString()}</p>{r.content.dimensions.map((d,i)=><p key={i}><strong>{d.name}：</strong>{d.evaluation}</p>)}<p className="hiring-review-text">{r.content.notes}</p><p>回答证据：{r.content.evidenceTurns.join('、')||'未引用'}</p></article>)}</details>
    {data.canPublish&&<section className="hiring-stage-editor"><h3>发布候选人反馈</h3><p>这里填写的内容将对候选人公开。内部评语与 AI 原始报告不会自动发布。</p><label>采用的人工评审<select value={revisionId} onChange={e=>{setRevisionId(e.target.value);setConfirm(false)}}><option value="">选择已提交修订</option>{data.history.map(r=><option key={r.id} value={r.id}>{data.reviews.find(v=>v.reviewerId===r.reviewerId)?.reviewerName} · 修订 {r.revision} · {decisionLabels[r.content.decision]}</option>)}</select></label><label>公开反馈<textarea rows={5} maxLength={5000} value={feedback} onChange={e=>{setFeedback(e.target.value);setConfirm(false)}}/></label>
      {selectedRevision?.content.decision==='NEXT_ROUND'&&<p>发布后可在“岗位与投递 → 面试批次”选择该候选人，创建下一轮邀请。</p>}
      {confirm?<div className="hiring-feedback-preview"><h4>候选人将看到</h4><strong>{decisionLabels[selectedRevision?.content.decision??'']}</strong><p className="hiring-review-text">{feedback}</p><button className="hiring-primary" disabled={busy} onClick={()=>void action(`${base}/publish`,{revision:data.publications[0]?.revision??0,reviewRevisionId:Number(revisionId),decision:selectedRevision?.content.decision,feedback})}>确认发布反馈</button><button disabled={busy} onClick={()=>setConfirm(false)}>返回编辑</button></div>:<button disabled={busy||!selectedRevision||!feedback.trim()||!finished} onClick={()=>setConfirm(true)}>预览公开反馈</button>}</section>}
    <FeedbackList items={data.publications}/>
  </div>
}
export function FeedbackList({items}:{items:Feedback[]}) {return <section className="hiring-feedback-list"><h3>企业已发布反馈</h3>{items.length===0?<p>企业尚未发布反馈。</p>:items.map((f,i)=><article className="hiring-stage-editor" key={f.revision}><strong>{decisionLabels[f.decision]} {i===0?'· 最新反馈':''}</strong><p className="hiring-review-text">{f.feedback}</p><p>发布于 {new Date(f.publishedAt).toLocaleString()} · 第 {f.revision} 版</p></article>)}</section>}
export function CandidateFeedback({id}:{id:string}) {const data=useHiringLoad<Feedback[]>(`/api/candidate/invitations/${id}/feedback`);return <><HiringError message={data.error}/>{data.data&&<FeedbackList items={data.data}/>}</>}
function TurnEvaluation({turn}:{turn:EvidenceTurn}) {
  const e=turn.evaluation
  return <div>{e?<><h4>AI 逐题评价 · 参考分 {e.score}</h4><p>已覆盖：{e.coveredPoints.join('、')||'暂无记录'}</p>{e.missingPoints.map((p,i)=><p key={i}><strong>未覆盖：{p.keyPoint}</strong> · {p.why}</p>)}{e.factualIssues.map((p,i)=><p key={i}>待核实事实：{p}</p>)}<p>评估模型：{e.model}</p></>:<p>本题 AI 评估尚未完成或不适用，不能视为零分。</p>}
    {!!turn.rubric?.length&&<section><h4>冻结评分依据</h4>{turn.rubric.map((r,i)=><p key={i}><strong>{r.keyPoint}</strong> · {r.acceptanceHint}</p>)}</section>}
    {turn.knowledge?.chunks.length?<section><h4>本题知识证据</h4>{turn.knowledge.chunks.map(c=><article key={c.pointId}><strong>{c.filename} {c.section} {c.pageNumber!=null?`第 ${c.pageNumber} 页`:''}{e?.citedSourceIds.includes(c.pointId)?' · AI 已引用':''}</strong><p className="hiring-review-text">{c.content}</p></article>)}</section>:<p>本题未配置企业知识证据，按通用知识评价。</p>}
  </div>
}
