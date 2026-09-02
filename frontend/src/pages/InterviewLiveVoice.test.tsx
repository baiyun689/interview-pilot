import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakeMediaRecorder, FakeXHR, recorderTestEnv } from '../test/voiceFakes'
import type { VoiceRecorderEnvironment } from '../voice/useVoiceRecorder'
import { InterviewLivePage } from './InterviewLivePage'

/**
 * 面试页语音交互（Task 10）页面级测试。假定时器驱动语音轮询（1.5s）与
 * 转写轮询（1s）；录音用 recorderTestEnv 注入；上传用 FakeXHR 驱动。
 */

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 组装 SSE 流响应：每块 event:xxx + data:yyy，块间空行。 */
function streamResponse(events: { name: string; data: unknown }[]) {
  const body = events.map((event) => `event: ${event.name}\ndata: ${JSON.stringify(event.data)}\n\n`).join('')
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

const askedTurn = {
  turnNo: 1, status: 'ASKED', phase: 'SELF_INTRODUCTION', questionType: 'SELF_INTRODUCTION',
  question: '请先做一个简短的自我介绍，重点说明与你应聘的 Java 后端岗位最相关的经历。',
  askedAt: '2026-09-01T08:00:00Z', answer: null, answeredAt: null,
}

const baseSession = {
  sessionId: 'session-1', resumeId: null, jobTitle: 'Java 后端开发工程师',
  jdText: '负责后端系统设计与开发', difficulty: 'MEDIUM', interviewSize: 'STANDARD',
  jobSourceType: 'PRESET', currentTurnNo: 1, currentMainQuestionNo: 1,
  totalMainQuestionCount: 9, providerId: 'dashscope', modelName: 'qwen-plus',
  preparationTaskId: 'task-1', safeError: null, interviewMode: 'VOICE',
}

const capabilities = {
  enabled: true,
  supportedMimeTypes: ['audio/webm', 'audio/ogg'],
  maxRecordingSeconds: 300,
  maxUploadBytes: 8388608,
  ttsEnabled: true,
}

/** 无语音（NOT_AVAILABLE）默认视图：大多数录音流程测试不需要语音装置。 */
const noSpeechView = { speechId: null, status: 'NOT_AVAILABLE', mediaUrl: null, retryable: false, safeError: null }

function voiceSession(turns = [askedTurn]) {
  return { ...baseSession, status: 'INTERVIEWING', turns }
}

function readyRecording(rawTranscript: string) {
  return json({
    recordingId: 'rec-1', turnNo: 1, status: 'READY', rawTranscript,
    durationMillis: 12000, retryable: false, safeError: null,
  })
}

interface LiveRoutes {
  session?: (url: string, init?: RequestInit) => Response
  capabilities?: () => Response
  speech?: () => Response
  speechRetry?: () => Response
  recordingView?: () => Response
  transcriptionRetry?: () => Response
  discard?: () => Response
  /** 答案 SSE；调用方可从 init.body 断言提交载荷 */
  stream?: (url: string, init?: RequestInit) => Response
}

function liveFetch(routes: LiveRoutes = {}) {
  const session = routes.session ?? (() => json(voiceSession()))
  const stream = routes.stream ?? (() => defaultStream())
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    // 顺序敏感：先匹配更具体的路径，再落到会话查询
    if (url.includes('/answers/stream')) return stream(url, init)
    if (url.endsWith('/speech/retry')) return routes.speechRetry?.() ?? json({}, 202)
    if (url.includes('/turns/1/speech')) return routes.speech?.() ?? json(noSpeechView)
    if (url.includes('/voice-recordings') && url.endsWith('/retry')) return routes.transcriptionRetry?.() ?? json({}, 202)
    if (url.includes('/voice-recordings') && url.endsWith('/discard')) return routes.discard?.() ?? json({}, 204)
    if (url.includes('/voice-recordings')) return routes.recordingView?.() ?? json({ recordingId: 'rec-1', turnNo: 1, status: 'UPLOADED', rawTranscript: null, durationMillis: null, retryable: false, safeError: null })
    if (url.endsWith('/api/voice/capabilities')) return routes.capabilities?.() ?? json(capabilities)
    if (url.includes('/api/interviews/session-1')) return session(url, init)
    throw new Error(`unexpected request: ${url}`)
  })
}

function defaultStream() {
  return streamResponse([
    { name: 'ACCEPTED', data: { type: 'ACCEPTED', sessionId: 'session-1', turnNo: 1, payload: { requestId: 'r-1', replayed: false } } },
    { name: 'RESULT', data: { type: 'RESULT', sessionId: 'session-1', turnNo: 1, payload: { completedTurnNo: 1, status: 'INTERVIEWING', nextTurn: null, idempotentReplay: false } } },
  ])
}

let playMock: ReturnType<typeof vi.fn>

function renderLive(env?: Partial<VoiceRecorderEnvironment>, routes: LiveRoutes = {}) {
  vi.stubGlobal('fetch', liveFetch(routes))
  return render(<MemoryRouter initialEntries={['/interviews/session-1']} future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
    <Routes>
      <Route path="/interviews/:sessionId" element={<InterviewLivePage env={env} />} />
    </Routes>
  </MemoryRouter>)
}

/** 推进假定时器并冲刷微任务（fetch/XHR promise 链）。 */
async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

const flush = () => advance(1)

/** 走完「开始录音 → 停止 → 上传成功」进入转写中。 */
async function recordAndUpload() {
  fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
  await advance(50)
  fireEvent.click(screen.getByRole('button', { name: '停止录音' }))
  fireEvent.click(screen.getByRole('button', { name: '上传录音' }))
  await flush()
  const xhr = FakeXHR.instances[FakeXHR.instances.length - 1]
  xhr.status = 202
  xhr.responseText = JSON.stringify({ recordingId: 'rec-1', status: 'UPLOADED', transcriptionTaskId: 'task-1' })
  act(() => { xhr.onload?.() })
  await flush()
}

/** 走到可编辑转写（TRANSCRIPT_READY）。 */
async function reachTranscriptReady(rawTranscript = '我负责订单系统的重构', routes: LiveRoutes = {}) {
  vi.stubGlobal('XMLHttpRequest', FakeXHR)
  const recordingView = () => readyRecording(rawTranscript)
  const fake = recorderTestEnv()
  renderLive(fake.env, { ...routes, recordingView: routes.recordingView ?? recordingView })
  await flush()
  await recordAndUpload()
  await advance(1000)
  expect(screen.getByText('语音转写结果，请确认')).toBeInTheDocument()
  return fake
}

beforeEach(() => {
  vi.useFakeTimers()
  FakeMediaRecorder.instances = []
  FakeXHR.instances = []
  FakeMediaRecorder.deferStopMs = 0
  URL.createObjectURL = vi.fn(() => 'blob:audio-1')
  URL.revokeObjectURL = vi.fn()
  playMock = vi.fn(() => Promise.resolve())
  HTMLMediaElement.prototype.play = playMock as unknown as typeof HTMLMediaElement.prototype.play
  HTMLMediaElement.prototype.pause = vi.fn()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  sessionStorage.clear()
  localStorage.clear()
})

describe('面试页语音交互：题目语音（Task 10 §13.2）', () => {
  it('题目文本先渲染；语音 202 后轮询到 READY 才渲染播放器并自动播放一次', async () => {
    let speechCalls = 0
    renderLive(undefined, {
      speech: () => {
        speechCalls += 1
        if (speechCalls === 1) {
          return json({ speechId: 's-1', status: 'PENDING', mediaUrl: null, retryable: false, safeError: null }, 202)
        }
        return json({ speechId: 's-1', status: 'READY', mediaUrl: '/api/interviews/session-1/speech/s-1/media', retryable: false, safeError: null })
      },
    })
    await flush()

    // 问题文本先出现，语音仍在加载（渐进增强：文本永远先展示）
    expect(screen.getByText(/请先做一个简短的自我介绍/)).toBeInTheDocument()
    expect(screen.getByText(/正在准备题目语音/)).toBeInTheDocument()
    expect(screen.queryByLabelText('题目语音')).not.toBeInTheDocument()

    // 1.5 秒后轮询到 READY → 播放器渲染
    await advance(1500)
    await flush()
    const audio = screen.getByLabelText('题目语音')
    fireEvent.canPlay(audio)
    expect(playMock).toHaveBeenCalledTimes(1)
    expect(screen.queryByText(/正在准备题目语音/)).not.toBeInTheDocument()
  })

  it('语音生成失败：显示提示且不影响录音答题；可重新生成语音', async () => {
    const speechRetry = vi.fn(() => json({}, 202))
    const fake = recorderTestEnv()
    renderLive(fake.env, {
      speech: () => json({ speechId: null, status: 'FAILED', mediaUrl: null, retryable: true, safeError: 'TTS 服务超时' }),
      speechRetry,
    })
    await flush()

    expect(screen.getByText(/题目语音生成失败/)).toBeInTheDocument()
    expect(screen.queryByLabelText('题目语音')).not.toBeInTheDocument()
    // 录音按钮可用：TTS 失败绝不阻塞答题
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '重新生成语音' }))
    await flush()
    expect(speechRetry).toHaveBeenCalled()
  })

  it('语音 NOT_AVAILABLE（未配置 TTS）：不显示播放器与加载提示，录音流程正常', async () => {
    const fake = recorderTestEnv()
    renderLive(fake.env)
    await flush()

    expect(screen.queryByLabelText('题目语音')).not.toBeInTheDocument()
    expect(screen.queryByText(/正在准备题目语音/)).not.toBeInTheDocument()
    expect(screen.queryByText(/题目语音生成失败/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()
  })

  it('文字模式会话：不请求题目语音、不显示录音面板，走既有文字输入', async () => {
    renderLive(undefined, {
      session: () => json({ ...baseSession, interviewMode: 'TEXT', status: 'INTERVIEWING', turns: [askedTurn] }),
    })
    await flush()

    expect(screen.getByLabelText('你的回答')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始录音' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('题目语音')).not.toBeInTheDocument()
    const urls = vi.mocked(fetch).mock.calls.map(([url]) => String(url))
    expect(urls.some((url) => url.includes('/turns/1/speech'))).toBe(false)
    expect(urls.some((url) => url.endsWith('/api/voice/capabilities'))).toBe(false)
  })
})

describe('面试页语音交互：录音与上传（Task 10）', () => {
  it('开始录音进入 RECORDING；暂停提示麦克风仍开启；停止后 RECORDED 可试听/重录/上传', async () => {
    const fake = recorderTestEnv()
    renderLive(fake.env)
    await flush()

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await advance(50)
    expect(fake.getUserMedia).toHaveBeenCalledWith({ audio: true })
    expect(screen.getByRole('timer', { name: '录音时长' })).toBeInTheDocument()
    expect(screen.getByRole('meter', { name: '音量电平' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '暂停录音' }))
    expect(screen.getByText(/麦克风仍开启/)).toBeInTheDocument()
    await advance(1000)
    expect(screen.getByRole('timer', { name: '录音时长' })).toHaveTextContent('00:00')
    fireEvent.click(screen.getByRole('button', { name: '继续录音' }))

    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))
    expect(screen.getByLabelText('录音试听')).toHaveAttribute('src', 'blob:audio-1')
    expect(screen.getByRole('button', { name: '上传录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改用文字回答' })).toBeInTheDocument()
  })

  it('双击上传只发起一次请求；进度显示后进入转写轮询，READY 展示可编辑转写', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    let recordingStatus = 'UPLOADED'
    const fake = recorderTestEnv()
    renderLive(fake.env, {
      recordingView: () => json({
        recordingId: 'rec-1', turnNo: 1, status: recordingStatus,
        rawTranscript: recordingStatus === 'READY' ? '我负责订单系统的重构' : null,
        durationMillis: 12000, retryable: false, safeError: null,
      }),
    })
    await flush()

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await advance(50)
    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))

    const uploadButton = screen.getByRole('button', { name: '上传录音' })
    fireEvent.click(uploadButton)
    fireEvent.click(uploadButton)
    await flush()
    expect(FakeXHR.instances).toHaveLength(1)
    const form = FakeXHR.instances[0].send.mock.calls[0][0] as FormData
    expect(form.get('uploadRequestId')).toMatch(/[0-9a-f-]{36}/)

    act(() => { FakeXHR.instances[0].upload.onprogress?.({ loaded: 25, total: 100, lengthComputable: true }) })
    expect(screen.getByText(/25%/)).toBeInTheDocument()

    FakeXHR.instances[0].status = 202
    FakeXHR.instances[0].responseText = JSON.stringify({ recordingId: 'rec-1', status: 'UPLOADED', transcriptionTaskId: 'task-1' })
    act(() => { FakeXHR.instances[0].onload?.() })
    await flush()
    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()

    // 每 1 秒轮询：UPLOADED 继续，READY 结束
    await advance(1000)
    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()
    recordingStatus = 'READY'
    await advance(1000)
    expect(screen.getByText('语音转写结果，请确认')).toBeInTheDocument()
    expect(screen.getByLabelText('转写内容')).toHaveValue('我负责订单系统的重构')
  })

  it('上传失败回到 RECORDED 并展示错误；重试复用同一 uploadRequestId', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const fake = recorderTestEnv()
    renderLive(fake.env, { recordingView: () => readyRecording('转写') })
    await flush()

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await advance(50)
    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))
    fireEvent.click(screen.getByRole('button', { name: '上传录音' }))
    await flush()

    const firstXhr = FakeXHR.instances[0]
    firstXhr.status = 500
    firstXhr.responseText = JSON.stringify({ code: 'VOICE_UPLOAD_TOO_LARGE', message: '录音文件超过大小限制' })
    act(() => { firstXhr.onload?.() })
    await flush()
    expect(screen.getByRole('alert')).toHaveTextContent('录音文件超过大小限制')
    expect(screen.getByRole('button', { name: '上传录音' })).toBeInTheDocument()

    // 重试：同一个 uploadRequestId（后端按它幂等去重）
    fireEvent.click(screen.getByRole('button', { name: '上传录音' }))
    await flush()
    expect(FakeXHR.instances).toHaveLength(2)
    const firstForm = firstXhr.send.mock.calls[0][0] as FormData
    const secondForm = FakeXHR.instances[1].send.mock.calls[0][0] as FormData
    expect(secondForm.get('uploadRequestId')).toBe(firstForm.get('uploadRequestId'))

    FakeXHR.instances[1].status = 202
    FakeXHR.instances[1].responseText = JSON.stringify({ recordingId: 'rec-1', status: 'UPLOADED', transcriptionTaskId: 'task-1' })
    act(() => { FakeXHR.instances[1].onload?.() })
    await flush()
    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()
  })

  it('上传中可改用文字回答：中止上传并切换到文字输入（评审 #6）', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const fake = recorderTestEnv()
    renderLive(fake.env, { recordingView: () => readyRecording('x') })
    await flush()

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await advance(50)
    fireEvent.click(screen.getByRole('button', { name: '停止录音' }))
    fireEvent.click(screen.getByRole('button', { name: '上传录音' }))
    await flush()
    expect(screen.getByText(/正在上传录音/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '改用文字回答' }))
    await flush()
    expect(screen.getByLabelText('你的回答')).toBeInTheDocument()
    expect(FakeXHR.instances[0].abort).toHaveBeenCalled()
    // 被中止的上传不再产生转写状态
    await advance(2000)
    expect(screen.queryByText(/正在转写录音/)).not.toBeInTheDocument()
    expect(fake).toBeTruthy()
  })
})

describe('面试页语音交互：转写轮询（Task 10）', () => {
  it('转写失败按 retryable 提供重试，并始终提供重录与文字输入；重试成功后恢复', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    let recordingStatus = 'UPLOADED'
    const fake = recorderTestEnv()
    renderLive(fake.env, {
      recordingView: () => json({
        recordingId: 'rec-1', turnNo: 1, status: recordingStatus,
        rawTranscript: recordingStatus === 'READY' ? '我负责订单系统的重构' : null,
        durationMillis: 12000, retryable: recordingStatus === 'FAILED',
        safeError: recordingStatus === 'FAILED' ? 'ASR 服务超时' : null,
      }),
    })
    await flush()
    await recordAndUpload()

    recordingStatus = 'FAILED'
    await advance(1000)
    expect(screen.getByRole('alert')).toHaveTextContent('ASR 服务超时')
    expect(screen.getByRole('button', { name: '重试转写' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改用文字回答' })).toBeInTheDocument()

    recordingStatus = 'READY'
    fireEvent.click(screen.getByRole('button', { name: '重试转写' }))
    await flush()
    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()
    await advance(1000)
    expect(screen.getByText('语音转写结果，请确认')).toBeInTheDocument()
    expect(screen.getByLabelText('转写内容')).toHaveValue('我负责订单系统的重构')
  })

  it('转写失败且不可重试时不显示重试按钮', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const fake = recorderTestEnv()
    renderLive(fake.env, {
      recordingView: () => json({ recordingId: 'rec-1', turnNo: 1, status: 'FAILED', rawTranscript: null, durationMillis: null, retryable: false, safeError: '模型不可用' }),
    })
    await flush()
    await recordAndUpload()
    await advance(1000)

    expect(screen.getByRole('alert')).toHaveTextContent('模型不可用')
    expect(screen.queryByRole('button', { name: '重试转写' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重新录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改用文字回答' })).toBeInTheDocument()
  })
})

describe('面试页语音交互：确认提交（Task 10）', () => {
  it('确认并提交：发送编辑后的文本，inputMode=VOICE 与 recordingId 随 SSE 载荷', async () => {
    let streamBodyJson: string | null = null
    const streamRoute = (url: string, init?: RequestInit) => {
      streamBodyJson = String(init?.body)
      return defaultStream()
    }
    const fake = await reachTranscriptReady('我负责订单系统的重构', { stream: streamRoute })
    fireEvent.change(screen.getByLabelText('转写内容'), { target: { value: '我负责订单系统的重构（修正术语）' } })
    fireEvent.click(screen.getByRole('button', { name: '确认并提交' }))
    await flush()

    const body = JSON.parse(streamBodyJson ?? '') as Record<string, unknown>
    expect(body).toMatchObject({
      answer: '我负责订单系统的重构（修正术语）',
      inputMode: 'VOICE',
      recordingId: 'rec-1',
    })
    expect(body.requestId).toEqual(expect.stringMatching(/[0-9a-f-]{36}/))
    expect(fake).toBeTruthy()
  })

  it('ANSWER_FAILED（HTTP 409）：展示重录或用文字重新提交指引，重试使用新的 requestId', async () => {
    // 评审 #4：ANSWER_FAILED 由 claim 同步校验抛出（HTTP 409），不是 SSE 事件
    const requestIds: string[] = []
    let turnFailed = false
    const failedTurn = { ...askedTurn, status: 'FAILED' }
    const streamRoute = (url: string, init?: RequestInit) => {
      requestIds.push((JSON.parse(String(init?.body)) as { requestId: string }).requestId)
      return json({ code: 'ANSWER_FAILED', message: 'The answer attempt failed; submit with a new requestId' }, 409)
    }
    const sessionRoute = () => json(turnFailed
      ? { ...baseSession, status: 'INTERVIEWING', turns: [failedTurn] }
      : voiceSession())
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const fake = recorderTestEnv()
    renderLive(fake.env, { recordingView: () => readyRecording('我负责订单系统的重构'), stream: streamRoute, session: sessionRoute })
    await flush()
    await recordAndUpload()
    await advance(1000)

    turnFailed = true
    fireEvent.click(screen.getByRole('button', { name: '确认并提交' }))
    await flush()
    expect(screen.getByRole('alert')).toHaveTextContent('本次回答处理失败，请重录或用文字重新提交')

    // 评审 Critical：turn FAILED 后语音面板保留，重置为可重录/文字回退的 IDLE
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改用文字回答' })).toBeInTheDocument()
    expect(screen.queryByText('语音转写结果，请确认')).not.toBeInTheDocument()

    // 文字回退重新提交：刷新恢复已清除旧 requestId，新请求使用新 id
    fireEvent.click(screen.getByRole('button', { name: '改用文字回答' }))
    await flush()
    fireEvent.change(screen.getByLabelText('你的回答'), { target: { value: '失败后的文字回答' } })
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))
    await flush()
    expect(requestIds).toHaveLength(2)
    expect(requestIds[1]).not.toBe(requestIds[0])
    expect(fake).toBeTruthy()
  })

  it('本轮处理失败（会话轮询刷新 turn FAILED）：面板保留并可重录/改用文字，旧录音被放弃', async () => {
    // 评审 Critical：ANSWER_STREAM_FAILED 后 turn 变 FAILED，语音面板不得消失
    let turnFailed = false
    const failedTurn = { ...askedTurn, status: 'FAILED' }
    const sessionRoute = () => json(turnFailed
      ? { ...baseSession, status: 'INTERVIEWING', turns: [failedTurn] }
      : voiceSession())
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    const fake = recorderTestEnv()
    renderLive(fake.env, { session: sessionRoute, recordingView: () => readyRecording('我负责订单系统的重构') })
    await flush()
    await recordAndUpload()
    await advance(1000)
    expect(screen.getByText('语音转写结果，请确认')).toBeInTheDocument()

    // 会话轮询刷新后 turn 变 FAILED：旧转写状态清空，回到可重录的 IDLE
    turnFailed = true
    await advance(2000)
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '改用文字回答' })).toBeInTheDocument()
    expect(screen.queryByText('语音转写结果，请确认')).not.toBeInTheDocument()
    // 旧录音已 best-effort 放弃
    const discardCall = vi.mocked(fetch).mock.calls.find(([url, init]) => String(url).includes('/discard') && init?.method === 'POST')
    expect(discardCall).toBeTruthy()
    expect(fake).toBeTruthy()
  })

  it('提交成功后进入下一轮：语音状态重置，可对新题开始录音', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    let advanced = false
    const nextTurn = {
      turnNo: 2, status: 'ASKED', phase: 'FUNDAMENTALS', questionType: 'MAIN',
      question: '请解释 JVM 内存模型与垃圾回收的关系。',
      askedAt: '2026-09-01T08:01:00Z', answer: null, answeredAt: null,
    }
    const sessionRoute = () => json(advanced
      ? { ...baseSession, status: 'INTERVIEWING', currentTurnNo: 2, currentMainQuestionNo: 2, turns: [askedTurn, nextTurn] }
      : voiceSession())
    const fake = recorderTestEnv()
    renderLive(fake.env, { session: sessionRoute, recordingView: () => readyRecording('我负责订单系统的重构') })
    await flush()
    await recordAndUpload()
    await advance(1000)

    advanced = true
    fireEvent.click(screen.getByRole('button', { name: '确认并提交' }))
    // 等待 SSE 完成 → refresh 返回第 2 轮 → 语音状态随换轮重置
    await advance(2000)

    expect(screen.getByText(/请解释 JVM 内存模型/)).toBeInTheDocument()
    expect(screen.queryByText('语音转写结果，请确认')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()
    expect(sessionStorage.getItem('interview-voice-recording:session-1')).toBeNull()
    expect(fake).toBeTruthy()
  })
})

describe('面试页语音交互：文字回退与恢复（Task 10）', () => {
  it('改用文字回答：best-effort 放弃已上传录音并切换到文字输入，提交不带 inputMode/recordingId', async () => {
    vi.stubGlobal('XMLHttpRequest', FakeXHR)
    let streamBody: Record<string, unknown> | null = null
    const streamRoute = (url: string, init?: RequestInit) => {
      streamBody = JSON.parse(String(init?.body)) as Record<string, unknown>
      return defaultStream()
    }
    const fake = recorderTestEnv()
    renderLive(fake.env, { recordingView: () => json({ recordingId: 'rec-1', turnNo: 1, status: 'TRANSCRIBING', rawTranscript: null, durationMillis: null, retryable: false, safeError: null }), stream: streamRoute })
    await flush()
    await recordAndUpload()

    fireEvent.click(screen.getByRole('button', { name: '改用文字回答' }))
    await flush()
    expect(screen.getByLabelText('你的回答')).toBeInTheDocument()
    const discardCall = vi.mocked(fetch).mock.calls.find(([url, init]) => String(url).includes('/discard') && init?.method === 'POST')
    expect(discardCall).toBeTruthy()

    fireEvent.change(screen.getByLabelText('你的回答'), { target: { value: '我改用文字回答' } })
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))
    await flush()
    expect(streamBody).toEqual({ requestId: expect.any(String), answer: '我改用文字回答' })
    expect(fake).toBeTruthy()
  })

  it('麦克风权限被拒：显示说明与文字输入入口，文字回答可端到端提交', async () => {
    let streamBody: Record<string, unknown> | null = null
    const streamRoute = (url: string, init?: RequestInit) => {
      streamBody = JSON.parse(String(init?.body)) as Record<string, unknown>
      return defaultStream()
    }
    const fake = recorderTestEnv({
      getUserMedia: vi.fn().mockRejectedValue(Object.assign(new Error('denied'), { name: 'NotAllowedError' })),
    })
    renderLive(fake.env, { stream: streamRoute })
    await flush()

    fireEvent.click(screen.getByRole('button', { name: '开始录音' }))
    await advance(50)
    expect(screen.getByRole('alert')).toHaveTextContent(/麦克风权限/)
    fireEvent.click(screen.getByRole('button', { name: '改用文字输入' }))
    expect(screen.getByLabelText('你的回答')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('你的回答'), { target: { value: '权限被拒后的文字回答' } })
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))
    await flush()
    expect(streamBody).toEqual({ requestId: expect.any(String), answer: '权限被拒后的文字回答' })
    expect(fake).toBeTruthy()
  })

  it('刷新恢复：按 sessionStorage 的 recordingId 恢复转写轮询，不重复上传', async () => {
    sessionStorage.setItem('interview-voice-recording:session-1', JSON.stringify({ sessionId: 'session-1', turnNo: 1, recordingId: 'rec-9', uploadRequestId: 'req-9' }))
    let polls = 0
    renderLive(undefined, {
      recordingView: () => {
        polls += 1
        if (polls === 1) {
          return json({ recordingId: 'rec-9', turnNo: 1, status: 'TRANSCRIBING', rawTranscript: null, durationMillis: null, retryable: false, safeError: null })
        }
        return json({ recordingId: 'rec-9', turnNo: 1, status: 'READY', rawTranscript: '刷新恢复的转写', durationMillis: 9000, retryable: false, safeError: null })
      },
    })
    await flush()

    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()
    await advance(1000) // 第一次轮询：仍 TRANSCRIBING
    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()
    await advance(1000) // 第二次轮询：READY
    expect(screen.getByText('语音转写结果，请确认')).toBeInTheDocument()
    expect(screen.getByLabelText('转写内容')).toHaveValue('刷新恢复的转写')
    // 恢复路径不再重新上传
    const uploadCalls = vi.mocked(fetch).mock.calls.filter(([url, init]) => String(url).includes('/voice-recordings') && init?.method === 'POST')
    expect(uploadCalls).toHaveLength(0)
  })

  it('刷新恢复遇 DISCARDED：终止轮询回到 IDLE、清除恢复条目，不无限轮询（评审）', async () => {
    sessionStorage.setItem('interview-voice-recording:session-1', JSON.stringify({ sessionId: 'session-1', turnNo: 1, recordingId: 'rec-9', uploadRequestId: 'req-9' }))
    let viewCalls = 0
    const fake = recorderTestEnv()
    renderLive(fake.env, {
      recordingView: () => {
        viewCalls += 1
        return json({ recordingId: 'rec-9', turnNo: 1, status: 'DISCARDED', rawTranscript: null, durationMillis: null, retryable: false, safeError: null })
      },
    })
    await flush()
    expect(screen.getByText(/正在转写录音/)).toBeInTheDocument()

    await advance(1000)
    expect(viewCalls).toBe(1)
    expect(screen.getByRole('button', { name: '开始录音' })).toBeInTheDocument()
    expect(sessionStorage.getItem('interview-voice-recording:session-1')).toBeNull()
    // 不再继续轮询
    await advance(5000)
    expect(viewCalls).toBe(1)
    expect(fake).toBeTruthy()
  })
})
