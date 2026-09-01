import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiClientError } from '../api/request'
import { fetchVoiceMediaBlob } from '../api/voice'

/**
 * 问题语音播放器（计划 §13.2）。媒体端点只接受 Authorization 头（后端
 * JwtAuthenticationFilter 仅解析请求头），`<audio src>` 无法携带该头，因此这里
 * 先用带认证的 fetch 拉取媒体 Blob，再以 Object URL 播放；短音频无需跨网络
 * Range 请求，seek 在 Blob 内即时完成。
 *
 * 自动播放语义：就绪后尝试播放一次；浏览器阻止（NotAllowedError）或用户
 * localStorage 偏好关闭（key: voice-autoplay，默认开启）时展示明显的播放按钮。
 * 卸载/换题时暂停播放、撤销 Object URL、移除事件监听。
 */
export const AUTOPLAY_STORAGE_KEY = 'voice-autoplay'

export interface QuestionSpeechPlayerProps {
  /** 媒体端点路径（相对或绝对），来自 QuestionSpeechView.mediaUrl */
  mediaUrl?: string
  /** 需要按需计算媒体 URL 时使用（mediaUrl 优先） */
  getMediaUrl?: () => string
  /** 覆盖 localStorage 自动播放偏好；缺省读 localStorage（默认开启） */
  autoPlay?: boolean
  onError?: (error: unknown) => void
  className?: string
}

/** 读取自动播放偏好：localStorage 值为 'off' 时关闭，其余情况默认开启。 */
export function readAutoplayPreference(storage?: Pick<Storage, 'getItem'>): boolean {
  try {
    // 评审 M6：window.localStorage 的属性访问本身也可能抛 SecurityError，
    // 必须放在 try 内部
    const source = storage ?? window.localStorage
    return source.getItem(AUTOPLAY_STORAGE_KEY) !== 'off'
  } catch {
    return true // 隐私模式等读取失败时按默认开启处理
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiClientError) return error.message
  return '语音加载失败，请稍后重试'
}

export function QuestionSpeechPlayer({
  mediaUrl,
  getMediaUrl,
  autoPlay,
  onError,
  className,
}: QuestionSpeechPlayerProps) {
  const audioRef = useRef<HTMLAudioElement>(null)
  const [playableUrl, setPlayableUrl] = useState<string | null>(null)
  const [blocked, setBlocked] = useState(false)
  const [loadError, setLoadError] = useState<unknown>()
  const [retryKey, setRetryKey] = useState(0)
  const startedRef = useRef(false)
  const objectUrlRef = useRef<string | null>(null)
  const onErrorRef = useRef(onError)
  onErrorRef.current = onError
  // 偏好只读取一次（挂载时固定），与录音环境同理
  const [preference] = useState(() => autoPlay ?? readAutoplayPreference())
  const resolvedUrl = mediaUrl ?? getMediaUrl?.() ?? ''

  useEffect(() => {
    if (!resolvedUrl) return
    let cancelled = false
    setLoadError(undefined)
    setBlocked(false)
    startedRef.current = false
    setPlayableUrl(null)
    fetchVoiceMediaBlob(resolvedUrl)
      .then((blob) => {
        if (cancelled) return
        const url = URL.createObjectURL(blob)
        objectUrlRef.current = url
        setPlayableUrl(url)
      })
      .catch((reason: unknown) => {
        if (cancelled) return
        setLoadError(reason)
        onErrorRef.current?.(reason)
      })
    return () => {
      cancelled = true
      audioRef.current?.pause()
      if (objectUrlRef.current) {
        URL.revokeObjectURL(objectUrlRef.current)
        objectUrlRef.current = null
      }
    }
  }, [resolvedUrl, retryKey])

  const tryPlay = useCallback(() => {
    const audio = audioRef.current
    if (!audio) return
    Promise.resolve(audio.play()).then(
      () => setBlocked(false),
      () => setBlocked(true),
    )
  }, [])

  // 就绪后自动播放一次（媒体元素 canplay 时）；每次新媒体只尝试一次
  useEffect(() => {
    const audio = audioRef.current
    if (!audio || !preference || startedRef.current) return
    const attempt = () => {
      if (startedRef.current) return
      startedRef.current = true
      Promise.resolve(audio.play()).then(
        () => setBlocked(false),
        () => {
          startedRef.current = false
          setBlocked(true)
        },
      )
    }
    audio.addEventListener('canplay', attempt, { once: true })
    if (audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) attempt()
    return () => audio.removeEventListener('canplay', attempt)
  }, [playableUrl, preference])

  if (loadError) {
    return (
      <div className={className ?? ''}>
        <div className="error-notice" role="alert"><p>{errorMessage(loadError)}</p></div>
        <div className="voice-actions">
          <button type="button" className="button button-secondary" onClick={() => setRetryKey((key) => key + 1)}>重新加载</button>
        </div>
      </div>
    )
  }

  if (!playableUrl) {
    return <p className="page-status" role="status">正在加载语音…</p>
  }

  const showPlayButton = !preference || blocked
  return (
    <div className={className ?? ''}>
      <audio ref={audioRef} src={playableUrl} aria-label="题目语音" controls={!showPlayButton} />
      {showPlayButton && (
        <button type="button" className="button button-primary" aria-label="播放语音" onClick={tryPlay}>播放语音</button>
      )}
    </div>
  )
}
