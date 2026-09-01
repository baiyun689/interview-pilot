import { useVoiceRecorder, type VoiceRecorderController, type VoiceRecorderEnvironment } from '../voice/useVoiceRecorder'

/**
 * 录音面板（计划 §13.2/§13.3）：开始/暂停/继续/停止/重录、mm:ss 计时、
 * 音量电平条、4:50 警告与自动停止提示、错误与文字回退信号。
 *
 * 两种用法：
 * - 自包含：不传 recorder，组件内部使用 useVoiceRecorder（env 可注入测试环境）；
 * - 受控：由父级（Task 10 面试页）传入 recorder controller，组件只负责渲染，
 *   父级持有同一 controller 以读取 blob/elapsedMs 并驱动上传与转写流程。
 *
 * 本组件不包含页面级导航逻辑；上传状态通过 uploading/uploadError/onUpload
 * 暴露给父级接线。
 */
export interface VoiceRecorderProps {
  /** 受控模式：传入父级持有的 recorder controller（缺省自包含） */
  recorder?: VoiceRecorderController
  /** 自包含模式的浏览器环境注入（测试用）；受控模式下忽略 */
  env?: Partial<VoiceRecorderEnvironment>
  /** 最长录音秒数，与 hook 的 maxRecordingSeconds 一致（默认 300） */
  maxRecordingSeconds?: number
  /** 上传进行中（Task 10 接线） */
  uploading?: boolean
  /** 上传失败信息（Task 10 接线） */
  uploadError?: unknown
  /** 上传回调（Task 10 接线），参数为最终 Blob */
  onUpload?: (blob: Blob) => void
  /** 文字回退信号：权限被拒或不支持录音时触发，由父级切换到文字输入 */
  onTextFallback?: () => void
  className?: string
}

function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function uploadErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '上传失败，请稍后重试'
}

export function VoiceRecorder({
  recorder: providedRecorder,
  env,
  maxRecordingSeconds,
  uploading = false,
  uploadError,
  onUpload,
  onTextFallback,
  className = '',
}: VoiceRecorderProps) {
  // 自包含模式下组件自己持有 recorder；受控模式下这个实例不会被启动，仅作占位
  const selfRecorder = useVoiceRecorder({ env, maxRecordingSeconds })
  const recorder = providedRecorder ?? selfRecorder
  const { state, elapsedMs, level, blobUrl, blob, nearLimit, autoStopped, unavailable, textFallback, error } = recorder
  const maxMs = (maxRecordingSeconds ?? 300) * 1000

  if (state === 'ERROR') {
    return (
      <section className={`voice-panel ${className}`.trim()}>
        <div className="error-notice" role="alert"><p>{error?.message ?? '录音失败，请稍后重试'}</p></div>
        <div className="voice-actions">
          <button type="button" className="button button-secondary" aria-label="重试录音" onClick={() => void recorder.start()}>重试录音</button>
          {textFallback && onTextFallback && (
            <button type="button" className="button button-secondary" aria-label="改用文字输入" onClick={onTextFallback}>改用文字输入</button>
          )}
        </div>
      </section>
    )
  }

  if (state === 'IDLE') {
    return (
      <section className={`voice-panel ${className}`.trim()}>
        {unavailable ? (
          <>
            <div className="error-notice" role="alert"><p>当前浏览器不支持语音录音，请改用文字输入。</p></div>
            {onTextFallback && (
              <div className="voice-actions">
                <button type="button" className="button button-secondary" aria-label="改用文字输入" onClick={onTextFallback}>改用文字输入</button>
              </div>
            )}
          </>
        ) : (
          <div className="voice-actions">
            <button type="button" className="button button-primary" aria-label="开始录音" onClick={() => void recorder.start()}>开始录音</button>
          </div>
        )}
      </section>
    )
  }

  if (state === 'REQUESTING_PERMISSION') {
    return <section className={`voice-panel ${className}`.trim()}><p className="page-status" role="status">正在请求麦克风权限…</p></section>
  }

  if (state === 'RECORDING' || state === 'PAUSED') {
    const levelPercent = Math.round(Math.min(1, Math.max(0, level)) * 100)
    return (
      <section className={`voice-panel ${className}`.trim()}>
        <div className="voice-status-row">
          <span className="voice-timer" role="timer" aria-label="录音时长">{formatDuration(elapsedMs)}</span>
          <span className="voice-meter" role="meter" aria-label="音量电平"
            aria-valuemin={0} aria-valuemax={100} aria-valuenow={levelPercent}>
            <span className="voice-meter-fill" style={{ width: `${levelPercent}%` }} />
          </span>
        </div>
        {state === 'PAUSED' && (
          <p className="voice-note" role="status">麦克风仍开启，暂停期间不计入录音时长</p>
        )}
        {nearLimit && (
          <p className="voice-warning" role="alert">即将到达最长录音时长（{formatDuration(maxMs)}），到时将自动停止</p>
        )}
        <div className="voice-actions">
          {state === 'RECORDING'
            ? <button type="button" className="button button-secondary" aria-label="暂停录音" onClick={recorder.pause}>暂停</button>
            : <button type="button" className="button button-secondary" aria-label="继续录音" onClick={recorder.resume}>继续</button>}
          <button type="button" className="button button-primary" aria-label="停止录音" onClick={() => void recorder.stop()}>停止</button>
        </div>
      </section>
    )
  }

  // RECORDED
  return (
    <section className={`voice-panel ${className}`.trim()}>
      <div className="voice-status-row">
        <span className="voice-timer" role="timer" aria-label="录音时长">{formatDuration(elapsedMs)}</span>
      </div>
      {autoStopped && <p className="voice-warning" role="status">已到达最长录音时长，录音已自动停止</p>}
      {blobUrl && <div className="voice-preview"><audio controls src={blobUrl} aria-label="录音试听" /></div>}
      {uploadError != null && <div className="error-notice" role="alert"><p>{uploadErrorMessage(uploadError)}</p></div>}
      <div className="voice-actions">
        {onUpload && blob && (
          <button type="button" className="button button-primary" aria-label="上传录音" disabled={uploading} onClick={() => onUpload(blob)}>
            {uploading ? '上传中…' : '上传并转写'}
          </button>
        )}
        <button type="button" className="button button-secondary" aria-label="重新录音" onClick={recorder.reset}>重录</button>
      </div>
    </section>
  )
}
