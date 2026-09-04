import { useCallback, useEffect, useRef, useState } from 'react'

const TARGET_SAMPLE_RATE = 16_000
// 100ms of 16kHz mono per WS frame: ~3.2KB PCM -> ~4.3KB base64 JSON, well under Tomcat's inbound
// buffer, and the cadence realtime ASR expects (a 1s frame both bloats each message and adds ~1s
// of recognition latency before server-side VAD can even see the speech).
const CHUNK_SAMPLES = TARGET_SAMPLE_RATE / 10
const PROCESSOR_BUFFER = 4096

/** Linear-resample to 16kHz mono (robust even when the device refuses a 16k getUserMedia hint). */
function downsample(buffer: Float32Array, inRate: number, outRate: number): Float32Array {
  if (inRate === outRate) return buffer
  const ratio = inRate / outRate
  const outLen = Math.max(1, Math.round(buffer.length / ratio))
  const out = new Float32Array(outLen)
  for (let i = 0; i < outLen; i += 1) {
    const idx = i * ratio
    const i0 = Math.floor(idx)
    const i1 = Math.min(i0 + 1, buffer.length - 1)
    const frac = idx - i0
    out[i] = buffer[i0] * (1 - frac) + buffer[i1] * frac
  }
  return out
}

function floatToPcm16(input: Float32Array): Int16Array {
  const out = new Int16Array(input.length)
  for (let i = 0; i < input.length; i += 1) {
    const s = Math.max(-1, Math.min(1, input[i]))
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff
  }
  return out
}

function pcm16ToBase64(pcm: Int16Array): string {
  const bytes = new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength)
  let binary = ''
  const block = 0x8000
  for (let i = 0; i < bytes.length; i += block) {
    binary += String.fromCharCode(...bytes.subarray(i, i + block))
  }
  return btoa(binary)
}

export interface UseRealtimeMicOptions {
  /** Invoked with one base64 s16le mono 16kHz frame (~100ms). */
  onChunk: (base64Pcm: string) => void
  /** Optional coarse input level 0..1 for UI feedback. */
  onLevel?: (level: number) => void
}

/**
 * Captures microphone audio and streams 16kHz s16le mono PCM frames. Uses ScriptProcessorNode for
 * zero-build-config, cross-browser reliability (an AudioWorklet is the future migration path).
 * Echo cancellation / noise suppression / AGC are enabled so the interviewer's own playback is not
 * re-recorded; server-side VAD owns utterance segmentation, so no client VAD dependency is needed.
 */
export function useRealtimeMic({ onChunk, onLevel }: UseRealtimeMicOptions) {
  const [active, setActive] = useState(false)
  const [muted, setMutedState] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const ctxRef = useRef<AudioContext | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const processorRef = useRef<ScriptProcessorNode | null>(null)
  const sourceRef = useRef<MediaStreamAudioSourceNode | null>(null)
  const carryRef = useRef<Int16Array[]>([])
  const carryLenRef = useRef(0)
  const mutedRef = useRef(false)
  const onChunkRef = useRef(onChunk)
  const onLevelRef = useRef(onLevel)

  useEffect(() => {
    onChunkRef.current = onChunk
    onLevelRef.current = onLevel
  }, [onChunk, onLevel])

  const stop = useCallback(() => {
    if (processorRef.current) {
      processorRef.current.disconnect()
      processorRef.current.onaudioprocess = null
      processorRef.current = null
    }
    if (sourceRef.current) {
      sourceRef.current.disconnect()
      sourceRef.current = null
    }
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    if (ctxRef.current) {
      void ctxRef.current.close().catch(() => undefined)
      ctxRef.current = null
    }
    carryRef.current = []
    carryLenRef.current = 0
    setActive(false)
  }, [])

  const start = useCallback(async () => {
    setError(null)
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      })
      streamRef.current = stream
      const ctx = new AudioContext()
      ctxRef.current = ctx
      const source = ctx.createMediaStreamSource(stream)
      sourceRef.current = source
      const processor = ctx.createScriptProcessor(PROCESSOR_BUFFER, 1, 1)
      processorRef.current = processor

      processor.onaudioprocess = (event) => {
        if (mutedRef.current) return
        const input = event.inputBuffer.getChannelData(0)
        const resampled = downsample(input, ctx.sampleRate, TARGET_SAMPLE_RATE)
        const pcm = floatToPcm16(resampled)
        if (pcm.length === 0) return

        let rms = 0
        for (let i = 0; i < pcm.length; i += 1) {
          const n = pcm[i] / 0x8000
          rms += n * n
        }
        onLevelRef.current?.(Math.min(1, Math.sqrt(rms / pcm.length) * 4))

        carryRef.current.push(pcm)
        carryLenRef.current += pcm.length
        while (carryLenRef.current >= CHUNK_SAMPLES) {
          const merged = new Int16Array(carryLenRef.current)
          let offset = 0
          for (const part of carryRef.current) {
            merged.set(part, offset)
            offset += part.length
          }
          const frame = merged.slice(0, CHUNK_SAMPLES)
          const rest = merged.slice(CHUNK_SAMPLES)
          carryRef.current = rest.length ? [rest] : []
          carryLenRef.current = rest.length
          onChunkRef.current?.(pcm16ToBase64(frame))
        }
      }

      source.connect(processor)
      // ScriptProcessor only fires while connected to destination; connect through a zero-gain node
      // to avoid monitoring our own mic while keeping the callback alive.
      const muteGain = ctx.createGain()
      muteGain.gain.value = 0
      processor.connect(muteGain)
      muteGain.connect(ctx.destination)
      setActive(true)
    } catch (err) {
      setError('无法访问麦克风，请检查浏览器权限')
      stop()
      throw err
    }
  }, [stop])

  const setMuted = useCallback((next: boolean) => {
    mutedRef.current = next
    setMutedState(next)
  }, [])

  useEffect(() => stop, [stop])

  return { start, stop, active, muted, setMuted, error }
}
