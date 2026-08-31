import { useEffect, useRef, useState } from 'react'
import { getAccessToken } from '../api/request'
import { createVoiceTicket } from '../api/interviews'

interface Props { sessionId: string; onTurnCompleted: () => Promise<unknown> }
interface VoiceEvent { type?: string; text?: string; final?: boolean; message?: string; audioBase64?: string }

export function VoiceInterviewPanel({ sessionId, onTurnCompleted }: Props) {
  const socket = useRef<WebSocket>()
  const context = useRef<AudioContext>()
  const processor = useRef<ScriptProcessorNode>()
  const source = useRef<MediaStreamAudioSourceNode>()
  const stream = useRef<MediaStream>()
  const [recording, setRecording] = useState(false)
  const [connected, setConnected] = useState(false)
  const [transcript, setTranscript] = useState('')
  const [message, setMessage] = useState('语音模式会复用当前文字面试状态。')

  useEffect(() => () => stop(), [sessionId])

  async function start() {
    if (recording) return
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    if (!getAccessToken()) { setMessage('登录状态已失效，请先重新登录。'); return }
    let ticket: string
    try { ticket = (await createVoiceTicket(sessionId)).ticket } catch { setMessage('无法创建语音会话，请重试。'); return }
    const ws = new WebSocket(`${protocol}://${window.location.host}/ws/voice-interviews/${sessionId}?ticket=${encodeURIComponent(ticket)}`)
    socket.current = ws
    ws.onopen = async () => {
      setConnected(true); setMessage('已连接，开始说话。')
      try {
        stream.current = await navigator.mediaDevices.getUserMedia({ audio: true })
        const audio = new AudioContext(); context.current = audio
        source.current = audio.createMediaStreamSource(stream.current)
        const node = audio.createScriptProcessor(4096, 1, 1); processor.current = node
        node.onaudioprocess = (event) => {
          if (socket.current?.readyState !== WebSocket.OPEN) return
          const pcm = downsample(event.inputBuffer.getChannelData(0), audio.sampleRate, 16000)
          socket.current.send(JSON.stringify({ type: 'audio', data: bytesToBase64(pcm) }))
        }
        source.current.connect(node); node.connect(audio.destination); setRecording(true)
      } catch { setMessage('无法访问麦克风，可改用文字模式。'); stop() }
    }
    ws.onmessage = (event) => {
      const data = JSON.parse(event.data) as VoiceEvent
      if (data.type === 'transcript' && data.text && data.final) setTranscript((old) => `${old} ${data.text}`.trim())
      if (data.type === 'error' || data.type === 'voice_disabled') setMessage(data.message ?? '语音服务不可用')
      if (data.type === 'turn_result') { setTranscript(''); void onTurnCompleted() }
      if (data.type === 'question' && data.audioBase64) void playPcm(data.audioBase64)
    }
    ws.onerror = () => setMessage('语音连接异常，请重试或使用文字模式。')
    ws.onclose = () => { setConnected(false); setRecording(false) }
  }

  function submit() {
    if (!transcript.trim() || socket.current?.readyState !== WebSocket.OPEN) return
    socket.current.send(JSON.stringify({ type: 'control', action: 'submit' })); setMessage('回答已提交，正在生成下一题。')
  }

  function stop() {
    processor.current?.disconnect(); source.current?.disconnect(); void context.current?.close()
    stream.current?.getTracks().forEach((track) => track.stop()); socket.current?.close()
    processor.current = undefined; source.current = undefined; stream.current = undefined; context.current = undefined; socket.current = undefined
    setRecording(false); setConnected(false)
  }

  async function playPcm(encoded: string) {
    const audio = context.current ?? new AudioContext(); context.current = audio
    const bytes = Uint8Array.from(atob(encoded), (char) => char.charCodeAt(0))
    const samples = new Int16Array(bytes.buffer), buffer = audio.createBuffer(1, samples.length, 24000)
    const channel = buffer.getChannelData(0); samples.forEach((sample, index) => { channel[index] = sample / 32768 })
    const player = audio.createBufferSource(); player.buffer = buffer; player.connect(audio.destination); player.start()
  }

  return <fieldset className="voice-panel">
    <legend>语音面试（WebSocket + Qwen ASR/TTS）</legend>
    <p className="empty-copy">{message}</p>
    {transcript && <p className="voice-transcript">识别结果：{transcript}</p>}
    <div className="form-row">
      {!connected ? <button type="button" className="button button-secondary" onClick={start}>开启语音</button> : <button type="button" className="button button-secondary" onClick={stop}>停止语音</button>}
      <button type="button" className="button button-primary" disabled={!recording || !transcript.trim()} onClick={submit}>提交语音回答</button>
    </div>
  </fieldset>
}

function downsample(input: Float32Array, from: number, to: number): Int16Array {
  const ratio = from / to, result = new Int16Array(Math.round(input.length / ratio))
  for (let index = 0; index < result.length; index++) {
    const sample = Math.max(-1, Math.min(1, input[Math.floor(index * ratio)])); result[index] = sample < 0 ? sample * 32768 : sample * 32767
  }
  return result
}

function bytesToBase64(bytes: Int16Array): string { return btoa(String.fromCharCode(...new Uint8Array(bytes.buffer))) }
