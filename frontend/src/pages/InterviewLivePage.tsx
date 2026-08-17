import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/request'
import { getInterview } from '../api/interviews'
import { postInterviewAnswerStream } from '../api/interviewStream'
import { adjustmentLabel, difficultyLabel, ErrorNotice, nextStepLabel, providerSnapshot, sessionStatusLabel } from '../components/InterviewUi'
import type { InterviewDecision, InterviewSession, InterviewStreamEvent } from '../types/interview'

interface Progressive {
  accepted?: { requestId: string; replayed: boolean }
  feedback?: string
  score?: number
  evidence?: string[]
  missingPoints?: string[]
  redFlags?: string[]
  decision?: InterviewDecision
  nextQuestion?: { question: string; targetCompetency: string; difficulty: 'EASY' | 'MEDIUM' | 'HARD' }
}

export function InterviewLivePage() {
  const { sessionId = '' } = useParams()
  const owner = useRef(0)
  const streamController = useRef<AbortController | null>(null)
  const [session, setSession] = useState<InterviewSession>()
  const [loadingError, setLoadingError] = useState<unknown>()
  const [answer, setAnswer] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [progressive, setProgressive] = useState<Progressive>({})
  const [streamError, setStreamError] = useState<unknown>()
  const [retryable, setRetryable] = useState(false)

  useEffect(() => {
    const id = ++owner.current, controller = new AbortController()
    setSession(undefined); setLoadingError(undefined); setAnswer(''); setSubmitting(false); setProgressive({}); setStreamError(undefined); setRetryable(false)
    getInterview(sessionId, controller.signal).then((value) => { if (owner.current === id) setSession(value) })
      .catch((error) => { if (owner.current === id && !(error instanceof DOMException && error.name === 'AbortError')) setLoadingError(error) })
    return () => { owner.current++; controller.abort(); streamController.current?.abort(); streamController.current = null }
  }, [sessionId])

  async function refresh(id: number, signal?: AbortSignal) {
    const current = await getInterview(sessionId, signal)
    if (owner.current === id) setSession(current)
    return current
  }

  function receive(id: number, event: InterviewStreamEvent) {
    if (owner.current !== id) return
    if (event.type === 'ACCEPTED') setProgressive((old) => ({ ...old, accepted: event.payload }))
    if (event.type === 'FEEDBACK') setProgressive((old) => ({ ...old, score: event.payload.score, feedback: event.payload.feedback, evidence: event.payload.evidence, missingPoints: event.payload.missingPoints, redFlags: event.payload.redFlags }))
    if (event.type === 'DECISION') setProgressive((old) => ({ ...old, decision: event.payload.decision }))
    if (event.type === 'NEXT_QUESTION') setProgressive((old) => ({ ...old, nextQuestion: event.payload }))
    if (event.type === 'ERROR') {
      setStreamError(new ApiClientError(0, event.payload.code || 'STREAM_ERROR', event.payload.message || '本轮处理失败', null))
      setRetryable(event.payload.retryable)
    }
  }

  async function submitAttempt() {
    const normalized = answer.trim()
    if (!session || submitting || !normalized || normalized.length > 20_000) return
    const id = owner.current, controller = new AbortController(); streamController.current = controller
    const requestId = crypto.randomUUID()
    setSubmitting(true); setProgressive({}); setStreamError(undefined); setRetryable(false)
    const submittedTurnNo = session.currentTurnNo
    let terminal = false
    const persistedAttemptFinished = (recovered: InterviewSession) =>
      recovered.turns.some((turn) => turn.requestId === requestId && (turn.status === 'COMPLETED' || turn.status === 'FAILED'))
      || recovered.currentTurnNo > submittedTurnNo
      || recovered.status !== 'INTERVIEWING'
    try {
      await postInterviewAnswerStream(sessionId, { requestId, answer: normalized }, {
        signal: controller.signal,
        onEvent: (name, event) => {
          if (name === 'COMPLETED' || event.type === 'COMPLETED') terminal = true
          if (name === 'ERROR' || event.type === 'ERROR') terminal = true
          receive(id, event)
        },
      })
      if (owner.current !== id) return
      if (terminal) {
        const recovered = await refresh(id, controller.signal)
        if (owner.current === id && persistedAttemptFinished(recovered)) setAnswer('')
      }
      else {
        const recovered = await refresh(id, controller.signal)
        if (owner.current === id && persistedAttemptFinished(recovered)) setAnswer('')
        else if (owner.current === id) { setStreamError(new ApiClientError(0, 'STREAM_DISCONNECTED', '连接中断，已恢复最新面试状态', null)); setRetryable(true) }
      }
    } catch (error) {
      if (owner.current !== id || (error instanceof DOMException && error.name === 'AbortError')) return
      try {
        const recovered = await refresh(id, controller.signal)
        const persisted = persistedAttemptFinished(recovered)
        if (persisted && owner.current === id) setAnswer('')
        if (!persisted && owner.current === id) {
          setStreamError(error)
          setRetryable(error instanceof ApiClientError && error.code === 'STREAM_DISCONNECTED')
        }
      } catch (recoveryError) { if (owner.current === id) { setStreamError(recoveryError); setRetryable(false) } }
    } finally { if (owner.current === id) setSubmitting(false) }
  }

  if (loadingError) return <section className="state-card"><h1>无法加载面试</h1><ErrorNotice error={loadingError} /></section>
  if (!session) return <p className="page-status" role="status">正在加载面试…</p>
  const canAnswer = session.status === 'INTERVIEWING'
  return <section className="live-page">
    <header className="interview-session-header"><div><p className="eyebrow">Live interview</p><h1>{session.jobTitle}</h1>{session.skillName && <p>面试方向：{session.skillName}</p>}<p>{providerSnapshot(session.providerId, session.modelName)}</p></div><span className="status-chip status-enabled">{sessionStatusLabel[session.status]}</span></header>
    <p className="interview-progress">进度 {session.currentTurnNo} / {session.totalTurnBudget}</p>
    <div className="conversation" aria-label="面试对话">
      {session.turns.map((turn) => <article className="turn-card" key={turn.turnNo}>
        <div className="message interviewer"><strong>面试官 · {turn.targetCompetency}</strong><p>{turn.question}</p></div>
        {turn.answer && <div className="message candidate"><strong>你的回答</strong><p>{turn.answer}</p></div>}
        {turn.feedback && <div className="turn-feedback"><p>{turn.feedback}</p>{turn.redFlags?.length ? <p>风险信号：{turn.redFlags.join('、')}</p> : null}{turn.decision && <Decision decision={turn.decision} />}</div>}
        {turn.status === 'FAILED' && <p className="error-notice" role="alert">本轮未完成，可以重新作答。</p>}
      </article>)}
      {(progressive.accepted || progressive.feedback || progressive.decision || progressive.nextQuestion) && <article className="stream-card" role="status" aria-label="本轮处理进度" aria-live="polite">
        {progressive.accepted && <p>{progressive.accepted.replayed ? '已恢复已接收的提交' : '回答已接收，正在生成反馈'}</p>}
        {progressive.feedback && <><strong>即时反馈 · {progressive.score} 分</strong><p>{progressive.feedback}</p>{progressive.evidence?.length ? <p>证据：{progressive.evidence.join('、')}</p> : null}{progressive.missingPoints?.length ? <p>待补充：{progressive.missingPoints.join('、')}</p> : null}{progressive.redFlags?.length ? <p>风险信号：{progressive.redFlags.join('、')}</p> : null}</>}
        {progressive.decision && <Decision decision={progressive.decision} />}
        {progressive.nextQuestion && <p><strong>下一题：</strong>{progressive.nextQuestion.question}<br /><span>{progressive.nextQuestion.targetCompetency} · {difficultyLabel[progressive.nextQuestion.difficulty]}</span></p>}
      </article>}
    </div>
    {canAnswer ? <div className="answer-panel"><label>你的回答<textarea rows={7} maxLength={20_000} value={answer} disabled={submitting} onChange={(e) => setAnswer(e.target.value)} /></label><ErrorNotice error={streamError} /><button type="button" className="button button-primary" disabled={submitting || !answer.trim()} onClick={submitAttempt}>{submitting ? '处理中…' : retryable ? '重新提交' : '提交回答'}</button></div> : <div className="state-card"><p>面试已进入{sessionStatusLabel[session.status]}阶段。</p></div>}
    {(session.status === 'EVALUATING' || session.status === 'COMPLETED') && <Link className="button button-primary report-link" to={`/interviews/${session.sessionId}/report`}>查看能力报告</Link>}
  </section>
}

function Decision({ decision }: { decision: InterviewDecision }) {
  return <div className="decision-grid"><span>流程动作：{nextStepLabel[decision.nextStep]}</span><span>难度调整：{adjustmentLabel[decision.difficultyAdjustment]}</span></div>
}
