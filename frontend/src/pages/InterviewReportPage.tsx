import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getInterview, getInterviewReport, retryInterviewReport } from '../api/interviews'
import { ErrorNotice, providerSnapshot } from '../components/InterviewUi'
import type { InterviewReportResult, InterviewReportStatus, InterviewSession } from '../types/interview'

function isResult(value: InterviewReportStatus | InterviewReportResult): value is InterviewReportResult {
  return 'report' in value
}

export function InterviewReportPage({ pollIntervalMs = 1500 }: { pollIntervalMs?: number }) {
  const { sessionId = '' } = useParams()
  const owner = useRef(0)
  const controller = useRef<AbortController | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout>>()
  const [session, setSession] = useState<InterviewSession>()
  const [status, setStatus] = useState<InterviewReportStatus>()
  const [result, setResult] = useState<InterviewReportResult>()
  const [error, setError] = useState<unknown>()
  const [retrying, setRetrying] = useState(false)

  useEffect(() => {
    const id = ++owner.current
    const sessionController = new AbortController()
    controller.current?.abort()
    if (timer.current) clearTimeout(timer.current)
    controller.current = null
    timer.current = undefined
    setSession(undefined); setStatus(undefined); setResult(undefined); setError(undefined); setRetrying(false)
    getInterview(sessionId, sessionController.signal).then((value) => { if (owner.current === id) setSession(value) })
      .catch((reason) => { if (owner.current === id && !(reason instanceof DOMException && reason.name === 'AbortError')) setError(reason) })
    void poll(id)
    return () => { owner.current++; sessionController.abort(); controller.current?.abort(); controller.current = null; if (timer.current) clearTimeout(timer.current); timer.current = undefined }
  // poll owns its sequential scheduling for this route instance.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  async function poll(id: number) {
    if (owner.current !== id) return
    const request = new AbortController(); controller.current = request
    try {
      const response = await getInterviewReport(sessionId, request.signal)
      if (owner.current !== id) return
      if (response.status === 200 && isResult(response.data)) { setResult(response.data); setStatus(undefined); return }
      const pending = response.data as InterviewReportStatus
      setStatus(pending); setError(undefined)
      if (!pending.retryable) timer.current = setTimeout(() => void poll(id), pollIntervalMs)
    } catch (reason) {
      if (owner.current === id && !(reason instanceof DOMException && reason.name === 'AbortError')) setError(reason)
    }
  }

  async function retry() {
    if (!status?.retryable || !status.taskId || retrying) return
    const id = owner.current, request = new AbortController(); controller.current = request
    setRetrying(true); setError(undefined)
    try {
      await retryInterviewReport(status.taskId, request.signal)
      if (owner.current === id) { setStatus(undefined); await poll(id) }
    } catch (reason) { if (owner.current === id && !(reason instanceof DOMException && reason.name === 'AbortError')) setError(reason) }
    finally { if (owner.current === id) setRetrying(false) }
  }

  return <section><Link className="back-link" to={`/interviews/${sessionId}`}>← 返回面试</Link>
    <header className="page-header"><h1>能力评估报告</h1>{session && <p>{providerSnapshot(session.providerId, session.modelName)}</p>}</header>
    <ErrorNotice error={error} />
    {!result && !error && (!status || !status.retryable) && <div className="state-card report-pending" role="status"><h2>报告生成中</h2><p>系统正在汇总各轮证据，无需刷新页面。</p></div>}
    {status?.retryable && <div className="state-card"><h2>报告生成未完成</h2><p>可以安全地重新触发报告任务。</p>{status.taskId && <button className="button button-primary" type="button" disabled={retrying} onClick={retry}>{retrying ? '重试中…' : '重试生成报告'}</button>}</div>}
    {result && <Report result={result} />}
  </section>
}

function Report({ result }: { result: InterviewReportResult }) {
  const entries = Object.entries(result.report.phaseScores)
  const phaseNames: Record<string, string> = { SELF_INTRODUCTION: '自我介绍', FUNDAMENTALS: '基础原理', PROJECT_EXPERIENCE: '项目经历', SCENARIO_TRADEOFF: '场景取舍' }
  return <div className="report-grid">
    <article className="score-hero"><span>综合评分</span><strong>{result.report.overallScore}</strong><p>{result.report.summary}</p></article>
    <article className="report-card score-chart"><h2>实际考查阶段</h2><div role="img" aria-label="阶段评分图">{entries.map(([name, score]) => <div className="score-row" key={name}><span>{phaseNames[name] ?? name}：{score} 分</span><div aria-hidden="true"><i style={{ width: `${score}%` }} /></div></div>)}</div></article>
    <article className="report-card"><h2>优势</h2><ul>{result.report.strengths.map((item) => <li key={item}>{item}</li>)}</ul></article>
    <article className="report-card"><h2>改进方向</h2><ul>{result.report.improvements.map((item) => <li key={item}>{item}</li>)}</ul></article>
    <article className="report-card"><h2>技术参考</h2>{result.report.technicalReferences.length ? <ul>{result.report.technicalReferences.map((item) => <li key={`${item.sourceId}:${item.note}`}>{item.note} <small>({item.sourceId})</small></li>)}</ul> : <p>本次报告未引用外部技术资料。</p>}</article>
    {result.report.conflictNotes.length > 0 && <article className="report-card"><h2>观点与参考资料差异</h2><ul>{result.report.conflictNotes.map((item) => <li key={item}>{item}</li>)}</ul></article>}
  </div>
}
