import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { InterviewLivePage } from './InterviewLivePage'

/**
 * 实时语音面试（WebSocket 通道）页面级测试：VOICE 会话默认进入实时对话面板，
 * 一键回退到旧录音链路，也可再切回实时。真实 WebSocket 用 FakeWebSocket 替换，
 * 保持在 CONNECTING（不驱动 open），因此不会触碰麦克风/AudioContext。
 */

const askedTurn = {
  turnNo: 1, status: 'ASKED', phase: 'SELF_INTRODUCTION', questionType: 'SELF_INTRODUCTION',
  question: '请先做一个简短的自我介绍。',
  askedAt: '2026-09-01T08:00:00Z', answer: null, answeredAt: null,
}

const voiceSession = {
  sessionId: 'session-1', resumeId: null, jobTitle: 'Java 后端开发工程师',
  jdText: '负责后端', difficulty: 'MEDIUM', interviewSize: 'STANDARD',
  jobSourceType: 'PRESET', currentTurnNo: 1, currentMainQuestionNo: 1,
  totalMainQuestionCount: 9, providerId: 'dashscope', modelName: 'qwen-plus',
  preparationTaskId: 'task-1', safeError: null, interviewMode: 'VOICE',
  status: 'INTERVIEWING', turns: [askedTurn],
}

const capabilities = {
  enabled: true, supportedMimeTypes: ['audio/webm'], maxRecordingSeconds: 300,
  maxUploadBytes: 8388608, ttsEnabled: true,
}

function json(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  })
}

/** 不真正联网的 WebSocket：记录实例与 URL，停留在 CONNECTING。 */
class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  readonly url: string
  readyState = FakeWebSocket.CONNECTING
  binaryType: string = ''
  onopen: ((ev?: unknown) => void) | null = null
  onmessage: ((ev: unknown) => void) | null = null
  onerror: ((ev?: unknown) => void) | null = null
  onclose: ((ev?: unknown) => void) | null = null
  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }
  send(): void { /* no-op */ }
  close(): void { this.readyState = FakeWebSocket.CLOSED }
  addEventListener(): void { /* no-op */ }
  removeEventListener(): void { /* no-op */ }
}

function renderLive() {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/api/voice/capabilities')) return json(capabilities)
    if (url.includes('/api/interviews/session-1')) return json(voiceSession)
    return json({})
  }))
  return render(<MemoryRouter initialEntries={['/interviews/session-1']} future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
    <Routes>
      <Route path="/interviews/:sessionId" element={<InterviewLivePage />} />
    </Routes>
  </MemoryRouter>)
}

beforeEach(() => {
  FakeWebSocket.instances = []
  vi.stubGlobal('WebSocket', FakeWebSocket)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('面试页实时语音通道（默认）', () => {
  it('VOICE 面试中默认挂载实时面板并向语音 WebSocket 发起连接，不渲染旧录音入口', async () => {
    renderLive()

    // 面试官当前题同时出现在对话区与实时气泡（渐进增强：文本先于语音）
    expect((await screen.findAllByText(/请先做一个简短的自我介绍/)).length).toBeGreaterThan(0)
    expect(await screen.findByText('连接中…')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '切换录音模式' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交回答' })).toBeDisabled()
    // 旧录音链路的“开始回答”思考入口不应出现
    expect(screen.queryByRole('button', { name: '开始回答' })).not.toBeInTheDocument()

    await waitFor(() => expect(FakeWebSocket.instances.length).toBeGreaterThan(0))
    const wsUrl = FakeWebSocket.instances[FakeWebSocket.instances.length - 1].url
    expect(wsUrl).toContain('/ws/voice-interview/session-1')
  })

  it('点“切换录音模式”回退到旧录音/上传面板，并可再切回实时通道', async () => {
    renderLive()
    expect(await screen.findByRole('button', { name: '切换录音模式' })).toBeInTheDocument()

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '切换录音模式' })) })

    // 旧录音面板：思考阶段的“开始回答” + 回到实时的入口
    expect(screen.getByRole('button', { name: '开始回答' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '切换实时对话' })).toBeInTheDocument()
    // 实时面板已卸载
    expect(screen.queryByText('连接中…')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '提交回答' })).not.toBeInTheDocument()

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: '切换实时对话' })) })
    expect(screen.getByRole('button', { name: '切换录音模式' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '开始回答' })).not.toBeInTheDocument()
  })
})
