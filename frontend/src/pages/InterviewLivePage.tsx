import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/request'
import { getInterview, retryInterviewPreparation, startInterview } from '../api/interviews'
import { postInterviewAnswerStream, type AnswerStreamInput } from '../api/interviewStream'
import { fetchVoiceCapabilities } from '../api/voice'
import { ErrorNotice, providerSnapshot, sessionStatusLabel } from '../components/InterviewUi'
import { QuestionSpeechPlayer } from '../components/QuestionSpeechPlayer'
import { VoiceRecorder } from '../components/VoiceRecorder'
import { createRequestId } from '../voice/ids'
import { useVoiceTurnFlow } from '../voice/useVoiceTurnFlow'
import type { VoiceRecorderEnvironment } from '../voice/useVoiceRecorder'
import type { InterviewPhase, InterviewSession, InterviewStreamEvent } from '../types/interview'
import type { VoiceCapabilities } from '../types/voice'

const phaseLabels: Record<InterviewPhase, string> = {
  SELF_INTRODUCTION: '自我介绍', FUNDAMENTALS: '基础原理',
  PROJECT_EXPERIENCE: '项目经历', SCENARIO_TRADEOFF: '场景取舍',
}

export interface InterviewLivePageProps {
  /** 录音环境的测试注入；生产环境无需传入 */
  env?: Partial<VoiceRecorderEnvironment>
}

export function InterviewLivePage({ env }: InterviewLivePageProps) {
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
  const [voiceCapabilities, setVoiceCapabilities] = useState<VoiceCapabilities | null>(null)
  const voice = useVoiceTurnFlow({ sessionId, session, maxRecordingSeconds: voiceCapabilities?.maxRecordingSeconds, env })
  const isVoiceSession = session?.interviewMode === 'VOICE'

  // 语音会话才拉取能力：只为录音时长上限；失败不影响面试（录音按默认上限）
  useEffect(() => {
    if (session?.interviewMode !== 'VOICE') return
    let cancelled = false
    fetchVoiceCapabilities()
      .then((caps) => { if (!cancelled) setVoiceCapabilities(caps) })
      .catch(() => { /* 能力探测失败仅回退默认时长上限 */ })
    return () => { cancelled = true }
  }, [session?.interviewMode])

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

  function handleStreamEvent(_name: string, event: InterviewStreamEvent) {
    receive(event)
    // 任务 6 语义：ANSWER_FAILED 表示本次尝试处理失败，重新提交必须使用新的 requestId；
    // 页面文案给出重录或用文字重新提交的指引（requestId 的新生由下方刷新恢复保证）
    if (event.type === 'ERROR' && event.payload.code === 'ANSWER_FAILED') {
      setError(new ApiClientError(0, 'ANSWER_FAILED', '本次回答处理失败，请重录或用文字重新提交', null))
    }
  }

  async function submitAnswer(input: Omit<AnswerStreamInput, 'requestId'>) {
    const normalized = input.answer.trim()
    if (!session || submitting || !normalized) return
    setSubmitting(true); setError(undefined); setProcessing('正在提交…')
    const controller = new AbortController(); streamController.current = controller
    const requestId = requestIdForTurn(requestKey, session.currentTurnNo)
    try {
      await postInterviewAnswerStream(sessionId, { requestId, answer: normalized, inputMode: input.inputMode, recordingId: input.recordingId }, {
        signal: controller.signal,
        onEvent: handleStreamEvent,
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

  async function submit() {
    if (!session || submitting) return
    await submitAnswer({ answer })
  }

  async function confirmVoice() {
    // 确认转写后走既有 SSE 答案接口；inputMode=VOICE + recordingId 绑定录音
    if (!session || submitting || !voice.recordingId) return
    await submitAnswer({ answer: voice.transcript, inputMode: 'VOICE', recordingId: voice.recordingId })
  }

  /** 文字输入面板（文字模式与语音模式文字回退共用；提交不带 inputMode，后端按 TEXT 处理）。 */
  function textAnswerPanel() {
    return <>
      <label>你的回答<textarea rows={7} maxLength={20_000} value={answer} disabled={submitting || currentTurnIsProcessing(session)} onChange={(event) => { setAnswer(event.target.value); sessionStorage.setItem(draftKey, event.target.value) }} /></label>
      {processing && <p role="status">{processing}</p>}
      <ErrorNotice error={error} />
      <button type="button" className="button button-primary" disabled={submitting || currentTurnIsProcessing(session) || !answer.trim()} onClick={submit}>{submitting ? '处理中…' : '提交回答'}</button>
    </>
  }

  /** 语音答题面板（计划 §13.2）：题目语音 + 录音/上传/转写/确认提交。 */
  function voiceAnswerPanel(currentSession: InterviewSession) {
    const currentTurn = currentSession.turns.find((turn) => turn.turnNo === currentSession.currentTurnNo)
    if (!currentTurn || currentTurn.status !== 'ASKED') return null
    if (voice.phase === 'TEXT_FALLBACK') return textAnswerPanel()
    const speechBlock = voice.speech.status !== 'NOT_AVAILABLE' && <div className="question-speech-area">
      {voice.speech.status === 'LOADING' && <p className="voice-note" role="status">正在准备题目语音…</p>}
      {voice.speech.status === 'READY' && <QuestionSpeechPlayer mediaUrl={voice.speech.view?.mediaUrl ?? undefined} />}
      {voice.speech.status === 'FAILED' && <>
        <p className="voice-note" role="status">题目语音生成失败，不影响文字答题。</p>
        {voice.speech.view?.retryable && <div className="voice-actions"><button type="button" className="button button-secondary" onClick={voice.retrySpeech}>重新生成语音</button></div>}
      </>}
    </div>
    return <>
      <ErrorNotice error={error} />
      {speechBlock}
      {(voice.phase === 'IDLE' || voice.phase === 'RECORDING' || voice.phase === 'RECORDED') && <>
        <VoiceRecorder recorder={voice.recorder} maxRecordingSeconds={voiceCapabilities?.maxRecordingSeconds} uploadError={voice.uploadError} onUpload={voice.upload} onTextFallback={voice.fallbackToText} />
        {voice.phase === 'RECORDED' && <div className="voice-actions"><button type="button" className="button button-secondary" onClick={voice.fallbackToText}>改用文字回答</button></div>}
      </>}
      {voice.phase === 'UPLOADING' && <p className="page-status" role="status">{voice.uploadProgress != null ? `正在上传录音… ${voice.uploadProgress}%` : '正在上传录音…'}</p>}
      {voice.phase === 'TRANSCRIBING' && <>
        <p className="page-status" role="status">正在转写录音，请稍候…</p>
        <div className="voice-actions"><button type="button" className="button button-secondary" onClick={voice.fallbackToText}>改用文字回答</button></div>
      </>}
      {voice.phase === 'TRANSCRIPT_READY' && <>
        <p className="voice-note" role="status">语音转写结果，请确认</p>
        <label>转写内容<textarea rows={7} maxLength={20_000} value={voice.transcript} onChange={(event) => voice.setTranscript(event.target.value)} /></label>
        {processing && <p role="status">{processing}</p>}
        <div className="voice-actions">
          <button type="button" className="button button-primary" disabled={submitting || !voice.transcript.trim()} onClick={confirmVoice}>{submitting ? '处理中…' : '确认并提交'}</button>
          <button type="button" className="button button-secondary" disabled={submitting} onClick={voice.resetRecording}>重新录音</button>
          <button type="button" className="button button-secondary" disabled={submitting} onClick={voice.fallbackToText}>改用文字回答</button>
        </div>
      </>}
      {voice.phase === 'TRANSCRIPT_FAILED' && <>
        <div className="error-notice" role="alert"><p>{voice.transcribeError?.message ?? '录音转写失败，请重试'}</p></div>
        <div className="voice-actions">
          {voice.transcribeError?.retryable && <button type="button" className="button button-secondary" onClick={voice.retryTranscription}>重试转写</button>}
          <button type="button" className="button button-secondary" onClick={voice.resetRecording}>重新录音</button>
          <button type="button" className="button button-secondary" onClick={voice.fallbackToText}>改用文字回答</button>
        </div>
      </>}
    </>
  }

  if (!session && !error) return <p className="page-status" role="status">正在加载面试…</p>
  if (!session) return <section className="state-card"><h1>无法加载面试</h1><ErrorNotice error={error} /></section>
  return <section className="live-page">
    <header className="interview-session-header"><div><h1>{session.jobTitle}</h1><p>{session.jobSourceType === 'PRESET' ? '预设岗位' : '自定义 JD'} · {session.interviewSize}</p><p>{providerSnapshot(session.providerId, session.modelName)}</p></div><span className="status-chip status-enabled">{sessionStatusLabel[session.status]}</span></header>
    <p className="interview-progress">主问题进度 {session.currentMainQuestionNo} / {session.totalMainQuestionCount}</p>
    {session.status === 'PREPARING' && <div className="state-card" role="status"><h2>正在准备完整题库</h2><p>基础、项目和场景题会一次生成并校验。页面每 2 秒自动刷新。</p></div>}
    {session.status === 'PREPARATION_FAILED' && <div className="state-card"><h2>题库准备失败</h2><p>{session.safeError ?? '题库输出未通过校验'}</p><button className="button button-primary" onClick={retryPreparation}>重新准备</button></div>}
    {session.status === 'READY' && <div className="state-card"><h2>题库准备完成</h2><p>共 {session.totalMainQuestionCount} 个主流程问题；除自我介绍外，每题包含 1～2 次追问。</p><button className="button button-primary" disabled={starting} onClick={start}>{starting ? '正在开始…' : '开始面试'}</button></div>}
    {session.turns.length > 0 && <div className="conversation" aria-label="面试对话">{session.turns.map((turn) => <article className="turn-card" key={turn.turnNo}><div className="message interviewer"><strong>面试官 · {phaseLabels[turn.phase]}{turn.questionType === 'FOLLOW_UP' ? ' · 追问' : ''}</strong><p>{turn.question}</p></div>{turn.answer && <div className="message candidate"><strong>你的回答</strong><p>{turn.answer}</p></div>}{turn.status === 'FAILED' && <p className="error-notice">本轮处理失败，可使用新的 requestId 重新提交。</p>}</article>)}</div>}
    {session.status === 'INTERVIEWING' && <div className="answer-panel">
      {isVoiceSession ? voiceAnswerPanel(session) : textAnswerPanel()}
    </div>}
    {session.status === 'EVALUATING' && <div className="state-card" role="status"><h2>正在生成最终报告</h2><p>评分只在全部问答完成后进行，页面会自动刷新。</p></div>}
    {session.status === 'EVALUATION_FAILED' && <div className="state-card"><h2>报告生成失败</h2><p>{session.safeError}</p><Link className="button button-primary" to={`/interviews/${session.sessionId}/report`}>前往报告页重试</Link></div>}
    {(session.status === 'EVALUATING' || session.status === 'EVALUATION_FAILED' || session.status === 'COMPLETED') && <Link className="button button-primary report-link" to={`/interviews/${session.sessionId}/report`}>查看最终报告</Link>}
  </section>
}

function currentTurnIsProcessing(session: InterviewSession | undefined) {
  return session?.turns.find((turn) => turn.turnNo === session.currentTurnNo)?.status === 'PROCESSING'
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
  // 非安全上下文（LAN http 调试）下降级生成，与语音上传 id 同一来源
  const requestId = createRequestId()
  sessionStorage.setItem(key, JSON.stringify({ turnNo, requestId }))
  return requestId
}
