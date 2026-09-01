import { useEffect, useRef, useState } from 'react'
import { BarChart3, CheckCircle2, Clock, MessagesSquare, XCircle, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router-dom'
import { listInterviews } from '../api/interviews'
import { difficultyLabel, ErrorNotice, providerSnapshot, sessionStatusLabel } from '../components/InterviewUi'
import type { InterviewHistory } from '../types/interview'

const statusIcons: Record<string, LucideIcon> = {
  PREPARING: Clock,
  READY: Clock,
  INTERVIEWING: MessagesSquare,
  EVALUATING: BarChart3,
  COMPLETED: CheckCircle2,
  PREPARATION_FAILED: XCircle,
  EVALUATION_FAILED: XCircle,
}

function toneFor(status: string) {
  return `history-tone-${status.toLowerCase()}`
}

function progressPercent(row: InterviewHistory) {
  if (!row.totalTurnBudget) return 0
  return Math.min(100, Math.round((row.currentTurnNo / row.totalTurnBudget) * 100))
}

export function InterviewHistoryPage() {
  const owner = useRef(0)
  const [rows, setRows] = useState<InterviewHistory[]>()
  const [error, setError] = useState<unknown>()
  useEffect(() => {
    const id = ++owner.current, controller = new AbortController()
    listInterviews(controller.signal).then((value) => { if (owner.current === id) setRows(value) })
      .catch((reason) => { if (owner.current === id && !(reason instanceof DOMException && reason.name === 'AbortError')) setError(reason) })
    return () => { owner.current++; controller.abort() }
  }, [])
  return <section><header className="page-header"><h1>面试记录</h1><p>回看每场面试的进度、模型快照与报告。</p></header>
    {Boolean(error) && <div className="state-card"><ErrorNotice error={error} /></div>}
    {!error && !rows && <div className="history-loading" role="status"><span className="history-spinner" aria-hidden /><p>正在加载面试记录…</p></div>}
    {rows?.length === 0 && <div className="history-empty">
      <div className="history-empty-icon" aria-hidden><MessagesSquare size={36} /></div>
      <h2>还没有面试</h2>
      <p>上传简历或直接选择面试方向，开启第一场 AI 自适应面试。</p>
      <Link className="button button-primary history-empty-cta" to="/interviews/new">开始第一场面试</Link>
    </div>}
    <div className="history-list">{rows?.map((row) => {
      const StatusIcon = statusIcons[row.status] ?? Clock
      return <article className="history-card" key={row.sessionId}>
        <div className="history-card-main">
          <div className={`history-status-icon ${toneFor(row.status)}`} aria-hidden><StatusIcon size={20} /></div>
          <div className="history-card-heading"><h2>{row.jobTitle}</h2>
            <p className="history-meta"><span>{row.jobSourceType === 'PRESET' ? '预设岗位' : '自定义 JD'} · {row.interviewSize}</span><span>{providerSnapshot(row.providerId, row.modelName)}</span></p>
          </div>
        </div>
        <dl className="history-stats">
          <div><dt>状态</dt><dd><span className={`status-chip ${toneFor(row.status)}`}>{sessionStatusLabel[row.status]}</span></dd></div>
          <div><dt>难度</dt><dd>{difficultyLabel[row.difficulty]}</dd></div>
          <div><dt>进度</dt><dd><div className="history-progress"><div className="history-progress-bar"><div className="history-progress-fill" style={{ width: `${progressPercent(row)}%` }} /></div><span>{row.currentTurnNo} / {row.totalTurnBudget}</span></div></dd></div>
        </dl>
        <div className="history-card-side">
          <p>创建时间：<time dateTime={row.createdAt}>{new Date(row.createdAt).toLocaleString('zh-CN')}</time></p>
          <p>完成时间：{row.completedAt ? <time dateTime={row.completedAt}>{new Date(row.completedAt).toLocaleString('zh-CN')}</time> : <span>未完成</span>}</p>
        </div>
        <div className="provider-actions">
          <Link className="detail-link" to={`/interviews/${row.sessionId}`}>{row.status === 'INTERVIEWING' ? '继续面试' : '查看详情'}</Link>
          {(row.status === 'EVALUATING' || row.status === 'EVALUATION_FAILED' || row.status === 'COMPLETED') && <Link className="detail-link" to={`/interviews/${row.sessionId}/report`}>查看报告</Link>}
        </div>
      </article>
    })}</div>
  </section>
}
