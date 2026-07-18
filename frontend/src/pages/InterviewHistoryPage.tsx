import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { listInterviews } from '../api/interviews'
import { difficultyLabel, ErrorNotice, providerSnapshot, sessionStatusLabel } from '../components/InterviewUi'
import type { InterviewHistory } from '../types/interview'

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
  return <section><header className="page-header"><p className="eyebrow">History</p><h1>面试记录</h1><p>回看每场面试的进度、模型快照与报告。</p></header>
    {Boolean(error) && <div className="state-card"><ErrorNotice error={error} /></div>}
    {!error && !rows && <p className="page-status" role="status">正在加载面试记录…</p>}
    {rows?.length === 0 && <div className="state-card"><h2>还没有面试</h2><Link to="/interviews/new">开始第一场面试</Link></div>}
    <div className="history-list">{rows?.map((row) => <article className="history-card" key={row.sessionId}><div><h2>{row.jobTitle}</h2>{row.skillName && <p>面试方向：{row.skillName}</p>}<p>{providerSnapshot(row.providerId, row.modelName)}</p><p>创建时间：<time dateTime={row.createdAt}>{new Date(row.createdAt).toLocaleString('zh-CN')}</time></p><p>完成时间：{row.completedAt ? <time dateTime={row.completedAt}>{new Date(row.completedAt).toLocaleString('zh-CN')}</time> : <span>未完成</span>}</p></div><dl><div><dt>状态</dt><dd>{sessionStatusLabel[row.status]}</dd></div><div><dt>难度</dt><dd>{difficultyLabel[row.difficulty]}</dd></div><div><dt>进度</dt><dd>{row.currentTurnNo} / {row.totalTurnBudget}</dd></div></dl><div className="provider-actions"><Link className="detail-link" to={`/interviews/${row.sessionId}`}>{row.status === 'INTERVIEWING' ? '继续面试' : '查看详情'}</Link>{(row.status === 'EVALUATING' || row.status === 'COMPLETED') && <Link className="detail-link" to={`/interviews/${row.sessionId}/report`}>查看报告</Link>}</div></article>)}</div>
  </section>
}
