import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/request'
import { getInterview, retryInterviewPreparation, startInterview } from '../api/interviews'
import { postInterviewAnswerStream } from '../api/interviewStream'
import { ErrorNotice, providerSnapshot, sessionStatusLabel } from '../components/InterviewUi'
import type { InterviewPhase, InterviewSession, InterviewStreamEvent } from '../types/interview'

const phaseLabels: Record<InterviewPhase, string> = {
  SELF_INTRODUCTION: '自我介绍', FUNDAMENTALS: '基础原理',
  PROJECT_EXPERIENCE: '项目经历', SCENARIO_TRADEOFF: '场景取舍',
}

export function InterviewLivePage() {
  const { sessionId = '' } = useParams()
  const draftKey = `interview-answer-draft:${sessionId}`
  const requestKey = `interview-answer-request:${sessionId}`
  const owner = useRef(0)
  const streamController = useRef<AbortController | null>(null)
  const [session, setSession] = useState<InterviewSession>()
  const [error, setError] = useState<unknown>()
  const [answer, setAnswer] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [processing, setProcessing] = useState('')
  const [starting, setStarting] = useState(false)

  useEffect(() => {
    const id = ++owner.current
    const controller = new AbortController()
    setAnswer(sessionStorage.getItem(draftKey) ?? '')
    const load = () => getInterview(sessionId, controller.signal).then((value) => {
      if (owner.current === id) {
        const pending = readPendingRequest(requestKey)
        if (pending && pending.turnNo !== value.currentTurnNo) {
          sessionStorage.removeItem(requestKey)
          sessionStorage.removeItem(draftKey)
          setAnswer('')
        }
        setSession(value)
      }
    }).catch((reason) => {
      if (owner.current === id && !(reason instanceof DOMException && reason.name === 'AbortError')) setError(reason)
    })
    void load()
    // Polling also recovers durable answer work after an SSE transport disconnect.
    const timer = window.setInterval(() => void load(), 2_000)
    return () => { owner.current++; controller.abort(); window.clearInterval(timer); streamController.current?.abort() }
  }, [sessionId, draftKey, requestKey])

  async function refresh() {
    const value = await getInterview(sessionId)
    setSession(value)
    return value
  }

  async function start() {
    if (starting) return
    setStarting(true); setError(undefined)
    try { await startInterview(sessionId); await refresh() }
    catch (reason) { setError(reason) }
    finally { setStarting(false) }
  }

  async function retryPreparation() {
    if (!session?.preparationTaskId) return
    setError(undefined)
    try { await retryInterviewPreparation(session.preparationTaskId); await refresh() }
    catch (reason) { setError(reason) }
  }

  function receive(event: InterviewStreamEvent) {
    if (event.type === 'ACCEPTED') setProcessing(event.payload.replayed ? '正在恢复已提交的回答…' : '回答已接收…')
    if (event.type === 'PROCESSING') setProcessing('正在生成下一题…')
    if (event.type === 'RESULT') setProcessing('')
    if (event.type === 'ERROR') {
      setProcessing('')
      setError(new ApiClientError(0, event.payload.code, event.payload.message, null))
    }
  }

  async function submit() {
    const normalized = answer.trim()
    if (!session || submitting || !normalized) return
    setSubmitting(true); setError(undefined); setProcessing('正在提交…')
    const controller = new AbortController(); streamController.current = controller
    const requestId = requestIdForTurn(requestKey, session.currentTurnNo)
    try {
      await postInterviewAnswerStream(sessionId, { requestId, answer: normalized }, {
        signal: controller.signal,
        onEvent: (_name, event) => receive(event),
      })
      const recovered = await refresh()
      if (recovered.currentTurnNo > session.currentTurnNo || recovered.status !== 'INTERVIEWING') {
        setAnswer(''); sessionStorage.removeItem(draftKey); sessionStorage.removeItem(requestKey)
      } else {
        const current = recovered.turns.find((turn) => turn.turnNo === session.currentTurnNo)
        if (!current || current.status === 'ASKED' || current.status === 'FAILED') {
          sessionStorage.removeItem(requestKey)
        }
      }
    } catch (reason) {
      if (!(reason instanceof DOMException && reason.name === 'AbortError')) {
        try {
          const recovered = await refresh()
          if (recovered.currentTurnNo > session.currentTurnNo || recovered.status !== 'INTERVIEWING') {
            setAnswer(''); sessionStorage.removeItem(draftKey); sessionStorage.removeItem(requestKey)
          } else {
            const current = recovered.turns.find((turn) => turn.turnNo === session.currentTurnNo)
            if (!current || current.status === 'ASKED' || current.status === 'FAILED') {
              sessionStorage.removeItem(requestKey)
            }
          }
        } catch { /* keep the original transport error */ }
        setError(reason)
      }
    } finally { setSubmitting(false); setProcessing('') }
  }

  if (!session && !error) return <p className="page-status" role="status">正在加载面试…</p>
  if (!session) return <section className="state-card"><h1>无法加载面试</h1><ErrorNotice error={error} /></section>
  return <section className="live-page">
    <header className="interview-session-header"><div><h1>{session.jobTitle}</h1><p>{session.jobSourceType === 'PRESET' ? '预设岗位' : '自定义 JD'} · {session.interviewSize}</p><p>{providerSnapshot(session.providerId, session.modelName)}</p></div><span className="status-chip status-enabled">{sessionStatusLabel[session.status]}</span></header>
    <p className="interview-progress">进度 {session.currentTurnNo} / {session.totalTurnBudget}</p>
    {session.status === 'PREPARING' && <div className="state-card" role="status"><h2>正在准备完整题库</h2><p>基础、项目和场景题会一次生成并校验。页面每 2 秒自动刷新。</p></div>}
    {session.status === 'PREPARATION_FAILED' && <div className="state-card"><h2>题库准备失败</h2><p>{session.safeError ?? '题库输出未通过校验'}</p><button className="button button-primary" onClick={retryPreparation}>重新准备</button></div>}
    {session.status === 'READY' && <div className="state-card"><h2>题库准备完成</h2><p>开始后将固定进行 {session.totalTurnBudget} 轮，追问也计入轮次。</p><button className="button button-primary" disabled={starting} onClick={start}>{starting ? '正在开始…' : '开始面试'}</button></div>}
    {session.turns.length > 0 && <div className="conversation" aria-label="面试对话">{session.turns.map((turn) => <article className="turn-card" key={turn.turnNo}><div className="message interviewer"><strong>面试官 · {phaseLabels[turn.phase]}{turn.questionType === 'FOLLOW_UP' ? ' · 追问' : ''}</strong><p>{turn.question}</p></div>{turn.answer && <div className="message candidate"><strong>你的回答</strong><p>{turn.answer}</p></div>}{turn.status === 'FAILED' && <p className="error-notice">本轮处理失败，可使用新的 requestId 重新提交。</p>}</article>)}</div>}
    {session.status === 'INTERVIEWING' && <div className="answer-panel"><label>你的回答<textarea rows={7} maxLength={20_000} value={answer} disabled={submitting || currentTurnIsProcessing(session)} onChange={(event) => { setAnswer(event.target.value); sessionStorage.setItem(draftKey, event.target.value) }} /></label>{processing && <p role="status">{processing}</p>}<ErrorNotice error={error} /><button type="button" className="button button-primary" disabled={submitting || currentTurnIsProcessing(session) || !answer.trim()} onClick={submit}>{submitting ? '处理中…' : '提交回答'}</button></div>}
    {session.status === 'EVALUATING' && <div className="state-card" role="status"><h2>正在生成最终报告</h2><p>评分只在全部问答完成后进行，页面会自动刷新。</p></div>}
    {session.status === 'EVALUATION_FAILED' && <div className="state-card"><h2>报告生成失败</h2><p>{session.safeError}</p><Link className="button button-primary" to={`/interviews/${session.sessionId}/report`}>前往报告页重试</Link></div>}
    {(session.status === 'EVALUATING' || session.status === 'EVALUATION_FAILED' || session.status === 'COMPLETED') && <Link className="button button-primary report-link" to={`/interviews/${session.sessionId}/report`}>查看最终报告</Link>}
  </section>
}

function currentTurnIsProcessing(session: InterviewSession) {
  return session.turns.find((turn) => turn.turnNo === session.currentTurnNo)?.status === 'PROCESSING'
}

function readPendingRequest(key: string): { turnNo: number; requestId: string } | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? 'null')
    return typeof value?.turnNo === 'number' && typeof value?.requestId === 'string' ? value : null
  } catch {
    sessionStorage.removeItem(key)
    return null
  }
}

function requestIdForTurn(key: string, turnNo: number) {
  const existing = readPendingRequest(key)
  if (existing?.turnNo === turnNo) return existing.requestId
  const requestId = crypto.randomUUID()
  sessionStorage.setItem(key, JSON.stringify({ turnNo, requestId }))
  return requestId
}
