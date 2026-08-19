import { useEffect, useRef, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'
import { getResume, retryResumeAnalysis } from '../api/resumes'
import { ApiClientError } from '../api/request'
import { ResumeStatusBadge } from '../components/ResumeStatusBadge'
import type { ResumeDetail } from '../types/resume'

const POLL_INTERVAL_MS = 2000

function safeError(error: unknown) {
  if (error instanceof ApiClientError) return { message: error.message, traceId: error.traceId }
  return { message: '操作失败，请稍后重试', traceId: null }
}

function sanitizedAnalysisError(value: string | null) {
  const cleaned = value?.replace(/[\u0000-\u001f\u007f]/g, ' ').trim()
  return cleaned ? cleaned.slice(0, 500) : '分析未能完成，请重新提交任务。'
}

export function ResumeDetailPage() {
  const { resumeId } = useParams()
  const id = Number(resumeId)
  const [resume, setResume] = useState<ResumeDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<{ message: string; traceId: string | null } | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const generation = useRef(0)
  const routeGeneration = useRef(0)

  useEffect(() => {
    const current = ++routeGeneration.current
    setRetrying(false)
    setNotice(null)
    return () => {
      if (routeGeneration.current === current) routeGeneration.current += 1
    }
  }, [id])

  useEffect(() => {
    const current = ++generation.current
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined

    async function poll() {
      try {
        const next = await getResume(id, controller.signal)
        if (generation.current !== current || controller.signal.aborted) return
        setResume(next)
        setError(null)
        setLoading(false)
        if (next.status === 'PENDING' || next.status === 'ANALYZING') {
          timer = setTimeout(() => void poll(), POLL_INTERVAL_MS)
        }
      } catch (loadError) {
        if (generation.current !== current || controller.signal.aborted) return
        setError(safeError(loadError))
        setLoading(false)
      }
    }

    if (!Number.isSafeInteger(id) || id <= 0) {
      setError({ message: '简历编号无效', traceId: null })
      setLoading(false)
    } else {
      void poll()
    }
    return () => {
      generation.current += 1
      controller.abort()
      if (timer) clearTimeout(timer)
    }
  }, [id, reloadKey])

  async function retry() {
    if (!resume || resume.status !== 'FAILED' || retrying) return
    const currentRoute = routeGeneration.current
    setRetrying(true)
    setError(null)
    setNotice(null)
    try {
      await retryResumeAnalysis(resume.analysisTaskId)
      if (routeGeneration.current !== currentRoute) return
      setResume({ ...resume, status: 'PENDING', analysisError: null })
      setNotice('重试请求已提交')
      setReloadKey((value) => value + 1)
    } catch (retryError) {
      if (routeGeneration.current !== currentRoute) return
      setError(safeError(retryError))
    } finally {
      if (routeGeneration.current === currentRoute) setRetrying(false)
    }
  }

  if (loading) return <p className="page-status" role="status">正在读取分析结果</p>

  return (
    <section>
      <Link className="back-link" to="/resumes"><ArrowLeft size={17} aria-hidden />返回简历列表</Link>
      {error && <div className="error-notice" role="alert"><p>{error.message}</p>{error.traceId && <small>追踪编号：{error.traceId}</small>}</div>}
      {notice && <p className="success-notice" role="status">{notice}</p>}
      {resume && (
        <>
          <header className="resume-detail-header">
            <div><h1>{resume.originalFilename}</h1><time dateTime={resume.createdAt}>{new Date(resume.createdAt).toLocaleString('zh-CN')}</time></div>
            <ResumeStatusBadge status={resume.status} />
          </header>

          {(resume.status === 'PENDING' || resume.status === 'ANALYZING') && (
            <div className="state-card analysis-progress" role="status"><h2>{resume.status === 'PENDING' ? '等待分析' : '分析中'}</h2><p>AI 正在整理技能、项目与面试关注点，本页会自动更新。</p></div>
          )}

          {resume.status === 'FAILED' && (
            <div className="state-card analysis-failed"><h2>分析失败</h2><p>{sanitizedAnalysisError(resume.analysisError)}</p><button className="button button-primary" disabled={retrying} onClick={() => void retry()}>{retrying ? '正在重试' : '重新分析'}</button></div>
          )}

          {resume.status === 'READY' && resume.evaluation && (
            <section className="evaluation-panel" aria-label="简历评测报告">
              <article className="profile-card evaluation-overview">
                <div className="overall-score">
                  <span className="overall-score-number">{resume.evaluation.overallScore}</span>
                  <span className="overall-score-total">/ 100</span>
                </div>
                <div className="score-list">
                  {([
                    ['项目经验', resume.evaluation.scoreDetail.projectScore, 40],
                    ['技能匹配', resume.evaluation.scoreDetail.skillMatchScore, 20],
                    ['内容完整', resume.evaluation.scoreDetail.contentScore, 15],
                    ['结构清晰', resume.evaluation.scoreDetail.structureScore, 15],
                    ['表达专业', resume.evaluation.scoreDetail.expressionScore, 10],
                  ] as const).map(([label, score, max]) => (
                    <div className="score-row" key={label}>
                      <span className="score-label">{label}</span>
                      <div className="score-bar"><div className="score-bar-fill" style={{ width: `${Math.min(100, (score / max) * 100)}%` }} /></div>
                      <span className="score-value">{score} / {max}</span>
                    </div>
                  ))}
                </div>
              </article>
              {(['高', '中', '低'] as const).map((priority) => {
                const items = resume.evaluation!.suggestions.filter((suggestion) => suggestion.priority === priority)
                if (!items.length) return null
                return (
                  <article className="profile-card suggestion-group" key={priority}>
                    <h2>{priority === '高' ? '高优先级建议' : priority === '中' ? '中优先级建议' : '低优先级建议'}</h2>
                    {items.map((suggestion, index) => (
                      <section className="suggestion-card" key={`${suggestion.category}-${index}`}>
                        <div className="suggestion-meta">
                          <span className={`priority-badge priority-${priority === '高' ? 'high' : priority === '中' ? 'medium' : 'low'}`}>{suggestion.priority}</span>
                          <span className="category-badge">{suggestion.category}</span>
                        </div>
                        <p className="suggestion-issue">{suggestion.issue}</p>
                        <p className="suggestion-recommendation">{suggestion.recommendation}</p>
                      </section>
                    ))}
                  </article>
                )
              })}
            </section>
          )}

          {resume.status === 'READY' && resume.profile && (
            <div className="profile-grid">
              <article className="profile-card profile-summary"><h2>候选人概览</h2><p>{resume.profile.summary}</p></article>
              <article className="profile-card"><h2>技术技能</h2>{resume.profile.technicalSkills.length ? <div className="chip-list">{resume.profile.technicalSkills.map((skill, index) => <span key={`${skill}-${index}`}>{skill}</span>)}</div> : <p className="empty-copy">暂无技能信息</p>}</article>
              <article className="profile-card profile-projects"><h2>项目经历</h2>{resume.profile.projects.length ? resume.profile.projects.map((project, index) => <section className="project-item" key={`${project.name}-${index}`}><h3>{project.name}</h3><p>{project.description}</p><div className="chip-list">{project.technologies.map((technology, techIndex) => <span key={`${technology}-${techIndex}`}>{technology}</span>)}</div></section>) : <p className="empty-copy">暂无项目经历</p>}</article>
              <article className="profile-card"><h2>优势</h2>{resume.profile.strengths.length ? <ul>{resume.profile.strengths.map((strength, index) => <li key={`${strength}-${index}`}>{strength}</li>)}</ul> : <p className="empty-copy">暂无优势信息</p>}</article>
              <article className="profile-card"><h2>风险与关注点</h2>{resume.profile.risks.length ? <ul>{resume.profile.risks.map((risk, index) => <li key={`${risk}-${index}`}>{risk}</li>)}</ul> : <p className="empty-copy">暂无风险提示</p>}</article>
            </div>
          )}

          {resume.status === 'READY' && !resume.profile && <div className="error-notice" role="alert"><p>分析结果缺少候选人画像，请稍后刷新。</p></div>}
        </>
      )}
    </section>
  )
}
